/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2;

import static retrofit2.Utils.methodError;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

abstract class ServiceMethod<T> {
  static <T> ServiceMethod<T> parseAnnotations(Retrofit retrofit, Method method) {
    //通过解析method的注解信息，实例化一个请求工厂对象，其包含请求方法,URL,header等请求信息。
    RequestFactory requestFactory = RequestFactory.parseAnnotations(retrofit, method);

    //获取方法的返回值类型，也就是我们自己在API service接口中声明的返回类型，如Call<ResponseBody>...
    Type returnType = method.getGenericReturnType();
    //如果是不能解决的类型，抛出异常
    if (Utils.hasUnresolvableType(returnType)) {
      throw methodError(
          method,
          "Method return type must not include a type variable or wildcard: %s",
          returnType);
    }
    //如果是 void 类型，抛出异常
    if (returnType == void.class) {
      throw methodError(method, "Service methods cannot return void.");
    }

    //解析注解，实例化一个 HttpServiceMethod 对象，并返回
    return HttpServiceMethod.parseAnnotations(retrofit, method, requestFactory);
  }

  abstract @Nullable T invoke(Object[] args);
}
