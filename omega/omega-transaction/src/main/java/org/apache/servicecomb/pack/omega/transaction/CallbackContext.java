/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.pack.omega.transaction;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.servicecomb.pack.omega.context.OmegaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CallbackContext {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, CallbackContextInternal> contexts = new ConcurrentHashMap<>();
  private final OmegaContext omegaContext;
  private final SagaMessageSender sender;

  public CallbackContext(OmegaContext omegaContext, SagaMessageSender sender) {
    this.omegaContext = omegaContext;
    this.sender = sender;
  }

  public void addCallbackContext(String key, Method compensationMethod, Object target) {
    compensationMethod.setAccessible(true);
    contexts.put(key, new CallbackContextInternal(target, compensationMethod));
  }

  public void apply(String globalTxId, String localTxId, String callbackMethod, Object... payloads) {
    CallbackContextInternal contextInternal = contexts.get(callbackMethod);
    String oldGlobalTxId = omegaContext.globalTxId();
    String oldLocalTxId = omegaContext.localTxId();
    try {
      omegaContext.setGlobalTxId(globalTxId);
      omegaContext.setLocalTxId(localTxId);
      contextInternal.callbackMethod.invoke(contextInternal.target, payloads);
      if (omegaContext.getAlphaMetas().isAkkaEnabled()) {
        sender.send(
            new TxCompensateAckSucceedEvent(omegaContext.globalTxId(), omegaContext.localTxId(),
                omegaContext.globalTxId()));
      }
      LOG.info("Callback transaction with global tx id [{}], local tx id [{}]", globalTxId, localTxId);
    } catch (IllegalAccessException | InvocationTargetException e) {
      if (omegaContext.getAlphaMetas().isAkkaEnabled()) {
        sender.send(
            new TxCompensateAckFailedEvent(omegaContext.globalTxId(), omegaContext.localTxId(),
                omegaContext.globalTxId()));
      }
      LOG.error(
          "Pre-checking for callback method " + contextInternal.callbackMethod.toString()
              + " was somehow skipped, did you forget to configure callback method checking on service startup?",
          e);
    } finally {
      omegaContext.setGlobalTxId(oldGlobalTxId);
      omegaContext.setLocalTxId(oldLocalTxId);
    }
  }

  private static final class CallbackContextInternal {
    private final Object target;

    private final Method callbackMethod;

    private CallbackContextInternal(Object target, Method callbackMethod) {
      this.target = target;
      this.callbackMethod = callbackMethod;
    }
  }
}
