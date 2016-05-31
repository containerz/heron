// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.spouts.kafka.common;

import java.util.Collections;
import java.util.List;

import com.twitter.heron.api.spout.MultiScheme;
import com.twitter.heron.api.tuple.Fields;
import com.twitter.heron.api.tuple.Values;

//CHECKSTYLE:OFF IllegalImport
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

@SuppressWarnings("serial")
public class StringMultiSchemeWithTopic
    implements MultiScheme {
  public static final String STRING_SCHEME_KEY = "str";

  public static final String TOPIC_KEY = "topic";

  public Iterable<List<Object>> deserializeWithTopic(String topic, byte[] bytes) {
    List<Object> items = new Values(StringScheme.deserializeString(bytes), topic);
    return Collections.singletonList(items);
  }

  @Override
  public Iterable<List<Object>> deserialize(byte[] bytes) {
    throw new NotImplementedException();
  }

  public Fields getOutputFields() {
    return new Fields(STRING_SCHEME_KEY, TOPIC_KEY);
  }
}