/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.tools.admin.command.fs;

import java.net.URI;
import org.kohsuke.args4j.Argument;


public class CopyCommand extends AbstractBasePinotFSCommand {
  @Argument(index = 0, required = true)
  private URI _srcUri;

  @Argument(index = 1, required = true)
  private URI _dstUri;

  @Override
  public boolean execute() throws Exception {
    if (!checkMatchingSchemes(_srcUri, _dstUri)) {
      System.out.println("The source and destination URIs must have matching schemes.");
      return false;
    }

    return getPinotFS(_srcUri).copy(_srcUri, _dstUri);
  }

  @Override
  public String description() {
    return "Copy files in a Pinot file system";
  }

  @Override
  public String getSubCommandUsage() {
    return "copyToLocal <sourceURI> <destinationURI>";
  }

  public void setSourceURI(URI srcUri) { _srcUri = srcUri; }

  public void setDestinationURI(URI dstUri) { _dstUri = dstUri; }
}