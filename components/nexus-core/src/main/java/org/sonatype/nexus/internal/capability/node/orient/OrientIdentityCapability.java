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
package org.sonatype.nexus.internal.capability.node.orient;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.common.node.orient.OrientNodeAccess;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.internal.capability.node.IdentityCapabilityConfiguration;
import org.sonatype.nexus.internal.capability.node.IdentityCapabilityDescriptor;
import org.sonatype.nexus.ssl.CertificateUtil;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Capability for exposing identity details.
 *
 * @since 3.0
 */
@Named(IdentityCapabilityDescriptor.TYPE_ID)
public class OrientIdentityCapability
    extends CapabilitySupport<IdentityCapabilityConfiguration>
{
  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("%s")
    String description(String nodeId);
  }

  private static final Messages messages = I18N.create(Messages.class);

  private final OrientNodeAccess nodeAccess;

  @Inject
  public OrientIdentityCapability(final OrientNodeAccess nodeAccess) {
    this.nodeAccess = checkNotNull(nodeAccess);
  }

  @Override
  protected IdentityCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new IdentityCapabilityConfiguration(properties);
  }

  // FIXME: This does not actually work, will have to add some sort of hook/condition/magic
  //@Override
  //protected void onRemove(final IdentityCapabilityConfiguration config) throws Exception {
  //  // HACK: until we have a condition to prevent this
  //  throw new IllegalStateException("Capability can not be removed");
  //}

  @Override
  protected String renderDescription() throws Exception {
    return messages.description(nodeAccess.getId());
  }

  @Override
  protected String renderStatus() throws Exception {
    return render(IdentityCapabilityDescriptor.TYPE_ID + "-status.vm", new TemplateParameters()
        .set("nodeId", nodeAccess.getId())
        .set("fingerprint", nodeAccess.getFingerprint())
        .set("pem", CertificateUtil.serializeCertificateInPEM(nodeAccess.getCertificate()))
        .set("detail", nodeAccess.getCertificate().toString())
    );
  }
}
