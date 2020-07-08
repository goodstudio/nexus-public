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
package org.sonatype.nexus.internal.email.rest;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Path;

import org.sonatype.nexus.email.EmailManager;

import static org.sonatype.nexus.internal.email.rest.EmailConfigurationApiResourceV1.RESOURCE_URI;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * v1 endpoint for email configuration REST API
 *
 * @since 3.25.0
 */
@Named
@Singleton
@Path(RESOURCE_URI)
public class EmailConfigurationApiResourceV1
  extends EmailConfigurationApiResource
{
  static final String RESOURCE_URI = V1_API_PREFIX + "/email";

  @Inject
  public EmailConfigurationApiResourceV1(final EmailManager emailManager) {
    super(emailManager);
  }
}
