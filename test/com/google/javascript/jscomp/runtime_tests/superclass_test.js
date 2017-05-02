/*
 * Copyright 2017 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

goog.require('goog.testing.jsunit');

function testClassInheritenceForSuperClass() {
  class C1 {}
  class C2 extends C1 {}
  class C3 extends C2 {}
  assertEquals(undefined, C1.superClass_);
  if (C2.superClass_) {
    assertEquals(C1.prototype, C2.superClass_);
  }
  if (C3.superClass_) {
    assertEquals(C2.prototype, C3.superClass_);
  }
}
