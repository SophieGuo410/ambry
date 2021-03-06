/*
 * Copyright 2010-2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * Modifications copyright (C) 2020 <Linkedin/zzmao>
 */
package com.github.ambry.network.http2;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.charset.StandardCharsets;


/**
 * Exception thrown when a GOAWAY frame is sent by the service.
 */
class GoAwayException extends IOException {
  private final String message;

  GoAwayException(long errorCode, ByteBuf debugData) {
    this.message = String.format(
        "GOAWAY received from service, requesting this stream be closed. " + "Error Code = %d, Debug Data = %s",
        errorCode, debugData.toString(StandardCharsets.UTF_8));
  }

  @Override
  public String getMessage() {
    return message;
  }
}
