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
package org.sonatype.nexus.bootstrap.jetty;

import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.jetty9.InstrumentedHandler;
import org.eclipse.jetty.server.Handler;

/**
 * Extension of {@link com.codahale.metrics.jetty9.InstrumentedHandler} that restores the delegate constructor.
 *
 * @since 2.15
 */
public final class NexusInstrumentedHandler
    extends InstrumentedHandler
{
  public NexusInstrumentedHandler(Handler delegate) {
    super(SharedMetricRegistries.getOrCreate("nexus"));
    setHandler(delegate);
  }
}