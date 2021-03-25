/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.pypi.internal;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.sonatype.nexus.common.io.SafeXml;
import org.sonatype.nexus.repository.pypi.PyPiAttributes;
import org.sonatype.nexus.repository.pypi.PyPiFormat;
import org.sonatype.nexus.repository.search.query.SearchQueryService;
import org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.pypi.PyPiAttributes.P_NAME;
import static org.sonatype.nexus.repository.pypi.PyPiAttributes.P_SUMMARY;
import static org.sonatype.nexus.repository.search.DefaultComponentMetadataProducer.REPOSITORY_NAME;
import static org.sonatype.nexus.repository.search.query.RepositoryQueryBuilder.unrestricted;

/**
 * Utility methods for working with PyPI search requests and responses.
 *
 * @since 3.1
 */
public final class PyPiSearchUtils
{
  // The subset of keys (format attributes) for XML-RPC queries (https://wiki.python.org/moin/PyPIXmlRpc) supported
  // by Nexus. pip only seems to use name and summary, so until we have a use case for it, limiting to those only.
  private static final Set<String> VALID_SEARCH_KEYS = new ImmutableSet.Builder<String>().add(
      P_NAME,
      P_SUMMARY
  ).build();

  private static final String METHOD_NAME_EXPRESSION = "/methodCall/methodName/text()";

  private static final String SEARCH_OPERATOR_EXPRESSION = "/methodCall/params/param/value/string/text()";

  private static final String MEMBER_LIST_EXPRESSION = "/methodCall/params/param/value/struct/member";

  private static final String PARAMETER_NAME_EXPRESSION = "name/text()";

  private static final String PARAMETER_VALUE_EXPRESSION = "value/array/data/value/string/text()";

  /**
   * XML-RPC request parser capable of ad-hoc parsing of search requests generated by pip. Only search requests are
   * expected (based on analyzing pip traffic) and exceptions are raised if the assumption is unmet.
   */
  public static QueryBuilder parseSearchRequest(final String repository, final InputStream in) throws Exception {
    checkNotNull(repository);
    checkNotNull(in);

    DocumentBuilderFactory factory = SafeXml.newdocumentBuilderFactory();
    factory.setValidating(false);

    DocumentBuilder builder = factory.newDocumentBuilder();
    XPathFactory xPathFactory = XPathFactory.newInstance();
    XPath xPath = xPathFactory.newXPath();
    Document document = builder.parse(in);

    // There are other XML-RPC operations that are supported by the PyPI XML-RPC API. However, at this time we only
    // support (a subset of) search operations specific to pip, so if something else comes in, we need to throw here.
    String methodName = (String) xPath.evaluate(METHOD_NAME_EXPRESSION, document, XPathConstants.STRING);
    if (!"search".equals(methodName)) {
      throw new UnsupportedOperationException("Only search methods supported, found: " + methodName);
    }

    // While we can also handle AND operations using ES, at this time we are only supporting the minimum feature set
    // necessary to support "pip search" requests. Since pip search only uses OR operations, that is all we support.
    String searchOperator = (String) xPath.evaluate(SEARCH_OPERATOR_EXPRESSION, document, XPathConstants.STRING);
    if (!"or".equals(searchOperator)) {
      throw new UnsupportedOperationException("Only or-search operations supported, found: " + searchOperator);
    }

    BoolQueryBuilder query = QueryBuilders.boolQuery();

    NodeList members = (NodeList) xPath.evaluate(MEMBER_LIST_EXPRESSION, document, XPathConstants.NODESET);
    for (int index = 0, count = members.getLength(); index < count; index++) {
      Node item = members.item(index);
      String name = (String) xPath.evaluate(PARAMETER_NAME_EXPRESSION, item, XPathConstants.STRING);
      if (!VALID_SEARCH_KEYS.contains(name)) {
        throw new UnsupportedOperationException("Search key not supported, found: " + name);
      }
      addSubqueries(xPath, query, name, item);
    }

    query.minimumNumberShouldMatch(1);
    query.filter(QueryBuilders.termQuery(REPOSITORY_NAME, repository));

    return query;
  }

  /**
   * Adds subqueries to a BoolQueryBuilder based on the contents of a particular search term in the request.
   */
  private static void addSubqueries(final XPath xPath, final BoolQueryBuilder query, final String name, final Node item)
      throws XPathExpressionException
  {
    checkNotNull(xPath);
    checkNotNull(query);
    checkNotNull(name);
    checkNotNull(item);
    String parameterName = "attributes.pypi." + name;
    NodeList values = (NodeList) xPath.evaluate(PARAMETER_VALUE_EXPRESSION, item, XPathConstants.NODESET);
    for (int index = 0, count = values.getLength(); index < count; index++) {

      Node value = values.item(index);
      String parameterValue = value.getTextContent().toLowerCase(Locale.ENGLISH);

      // Note that under normal circumstances, prefixing a wildcard query with * or ? is contraindicated according
      // to the ES docs. In this case it's the only way to get the same substring search behavior that PyPI seems
      // to produce, and we think the total number of components in a single repo will be small enough that the
      // penalty will be minimal in real-world terms. If search is running too slow, start looking through here.
      if (!parameterValue.contains("*")) {
        parameterValue = "*" + parameterValue + "*";
      }

      query.should(QueryBuilders.wildcardQuery(parameterName, parameterValue));
    }
  }

  public static List<PyPiSearchResult> pypiSearch(QueryBuilder query, SearchQueryService searchQueryService) {
    List<PyPiSearchResult> results = new ArrayList<>();
    for (SearchHit hit : searchQueryService.browse(unrestricted(query))) {
      Map<String, Object> source = hit.getSource();
      Map<String, Object> formatAttributes = (Map<String, Object>) source.getOrDefault(
          MetadataNodeEntityAdapter.P_ATTRIBUTES, Collections.emptyMap());
      Map<String, Object> pypiAttributes = (Map<String, Object>) formatAttributes.getOrDefault(PyPiFormat.NAME,
          Collections.emptyMap());
      String name = Strings.nullToEmpty((String) pypiAttributes.get(PyPiAttributes.P_NAME));
      String version = Strings.nullToEmpty((String) pypiAttributes.get(PyPiAttributes.P_VERSION));
      String summary = Strings.nullToEmpty((String) pypiAttributes.get(PyPiAttributes.P_SUMMARY));
      results.add(new PyPiSearchResult(name, version, summary));
    }
    return results;
  }

  /**
   * Writes a search response for the specified result.
   */
  public static String buildSearchResponse(final Collection<PyPiSearchResult> results) throws Exception {
    checkNotNull(results);
    StringWriter s = new StringWriter();
    XMLOutputFactory factory = XMLOutputFactory.newInstance();
    try (SearchResponseWriter writer = new SearchResponseWriter(factory.createXMLStreamWriter(s))) {
      writer.writePrologue();
      for (PyPiSearchResult result : results) {
        writer.writeEntry(result);
      }
      writer.writeEpilogue();
    }
    return s.toString();
  }

  /**
   * Parses an XML-RPC search response into a list of search results (such as for merging from different repositories).
   */
  static List<PyPiSearchResult> parseSearchResponse(final InputStream in) throws Exception {
    SAXParserFactory factory = SafeXml.newSaxParserFactory();
    factory.setValidating(false);
    factory.setNamespaceAware(false);

    SearchResponseHandler handler = new SearchResponseHandler();
    SAXParser parser = factory.newSAXParser();
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    parser.parse(in, handler);
    return handler.getResults();
  }

  /**
   * SAX handler to extract result information from a PyPI XML-RPC search result.
   */
  private static class SearchResponseHandler
      extends DefaultHandler
  {
    private List<PyPiSearchResult> results = new ArrayList<>();

    private String currentName = null;

    private String currentVersion = null;

    private String currentSummary = null;

    private String currentMemberName = null;

    private String currentMemberValue = null;

    private StringBuilder currentCharacters = null;

    private boolean fault = false;

    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
      if (fault) {
        return;
      }
      switch (qName) {
        case "fault":
          fault = true;
          break;
        case "member":
          currentMemberName = null;
          currentMemberValue = null;
          break;
        case "name":
          currentCharacters = new StringBuilder();
          break;
        case "string":
          currentCharacters = new StringBuilder();
          break;
        case "boolean":
          currentCharacters = new StringBuilder();
          break;
        default:
          // empty
      }
    }

    public void endElement(String uri, String localName, String qName) throws SAXException
    {
      if (fault) {
        return;
      }
      switch (qName) {
        case "struct":
          results.add(buildSearchResult());
          currentName = null;
          currentVersion = null;
          currentSummary = null;
          break;
        case "member":
          store(currentMemberName, currentMemberValue);
          currentMemberName = null;
          currentMemberValue = null;
          break;
        case "name":
          currentMemberName = currentCharacters.toString();
          currentCharacters = null;
          break;
        case "string":
          currentMemberValue = currentCharacters.toString();
          currentCharacters = null;
          break;
        case "boolean":
          currentMemberValue = currentCharacters.toString();
          currentCharacters = null;
          break;
        default:
          // empty
      }
    }

    public void characters(char[] ch, int start, int length) throws SAXException {
      if (!fault && currentCharacters != null) {
        currentCharacters.append(ch, start, length);
      }
    }

    private void store(String name, String value) {
      switch(name) {
        case "name":
          currentName = value;
          break;
        case "version":
          currentVersion = value;
          break;
        case "summary":
          currentSummary = value;
          break;
        default:
          // empty
      }
    }

    private PyPiSearchResult buildSearchResult() {
      return new PyPiSearchResult(
          Strings.nullToEmpty(currentName),
          Strings.nullToEmpty(currentVersion),
          Strings.nullToEmpty(currentSummary));
    }

    public List<PyPiSearchResult> getResults() {
      return results;
    }
  }

  private PyPiSearchUtils() {
    // empty
  }
}