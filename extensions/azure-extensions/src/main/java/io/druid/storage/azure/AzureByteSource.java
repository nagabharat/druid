/*
 * Druid - a distributed column store.
 *  Copyright 2012 - 2015 Metamarkets Group Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.druid.storage.azure;

import com.google.common.base.Throwables;
import com.google.common.io.ByteSource;
import com.microsoft.azure.storage.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;

public class AzureByteSource extends ByteSource
{

  final private AzureStorageContainer azureStorageContainer;
  final private String filePath;

  public AzureByteSource(
      AzureStorageContainer azureStorageContainer,
      String filePath
  )
  {
    this.azureStorageContainer = azureStorageContainer;
    this.filePath = filePath;
  }

  @Override
  public InputStream openStream() throws IOException
  {
    try {
      return azureStorageContainer.getBlobInputStream(filePath);
    }
    catch (StorageException | URISyntaxException e) {
      if (AzureUtils.AZURE_RETRY.apply(e)) {
        throw new IOException("Recoverable exception", e);
      }
      throw Throwables.propagate(e);
    }
  }
}
