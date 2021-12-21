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

import static retrofit2.Utils.throwIfFatal;

import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Timeout;

final class OkHttpCall<T> implements Call<T> {
  private final RequestFactory requestFactory;
  private final Object[] args;
  private final okhttp3.Call.Factory callFactory;
  private final Converter<ResponseBody, T> responseConverter;

  private volatile boolean canceled;

  @GuardedBy("this")
  private @Nullable okhttp3.Call rawCall;

  @GuardedBy("this") // Either a RuntimeException, non-fatal Error, or IOException.
  private @Nullable Throwable creationFailure;

  @GuardedBy("this")
  private boolean executed;

  OkHttpCall(
      RequestFactory requestFactory,
      Object[] args,
      okhttp3.Call.Factory callFactory,
      Converter<ResponseBody, T> responseConverter) {
    this.requestFactory = requestFactory;
    this.args = args;
    this.callFactory = callFactory;
    this.responseConverter = responseConverter;
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override
  public OkHttpCall<T> clone() {
    return new OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
  }

  @Override
  public synchronized Request request() {
    try {
      return getRawCall().request();
    } catch (IOException e) {
      throw new RuntimeException("Unable to create request.", e);
    }
  }

  @Override
  public synchronized Timeout timeout() {
    try {
      return getRawCall().timeout();
    } catch (IOException e) {
      throw new RuntimeException("Unable to create call.", e);
    }
  }

  /**
   * Returns the raw call, initializing it if necessary. Throws if initializing the raw call throws,
   * or has thrown in previous attempts to create it.
   */
  @GuardedBy("this")
  private okhttp3.Call getRawCall() throws IOException {
    okhttp3.Call call = rawCall;
    if (call != null) return call;

    // Re-throw previous failures if this isn't the first attempt.
    if (creationFailure != null) {
      if (creationFailure instanceof IOException) {
        throw (IOException) creationFailure;
      } else if (creationFailure instanceof RuntimeException) {
        throw (RuntimeException) creationFailure;
      } else {
        throw (Error) creationFailure;
      }
    }

    // Create and remember either the success or the failure.
    try {
      return rawCall = createRawCall();
    } catch (RuntimeException | Error | IOException e) {
      throwIfFatal(e); // Do not assign a fatal error to creationFailure.
      creationFailure = e;
      throw e;
    }
  }

  @Override
  public void enqueue(final Callback<T> callback) {
    //检查一下callback是否为空
    Objects.requireNonNull(callback, "callback == null");

    okhttp3.Call call;
    Throwable failure;

    synchronized (this) {
      //判断请求是否已经被执行
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;

      call = rawCall;
      failure = creationFailure;
      if (call == null && failure == null) {
        try {
          //构造原始请求
          call = rawCall = createRawCall();
        } catch (Throwable t) {
          throwIfFatal(t);
          failure = creationFailure = t;
        }
      }
    }

    if (failure != null) {
      //回调callback.onFailure()方法
      callback.onFailure(this, failure);
      return;
    }

    //请求是否被取消
    if (canceled) {
      call.cancel();
    }

    //执行call.enqueue方法
    call.enqueue(
        new okhttp3.Callback() {
          @Override
          public void onResponse(okhttp3.Call call, okhttp3.Response rawResponse) {
            Response<T> response;
            try {
              //解析最初始的response，转换成我们想要的格式类型
              response = parseResponse(rawResponse);
            } catch (Throwable e) {
              throwIfFatal(e);
              callFailure(e);
              return;
            }

            try {
              //回调callback.onResponse()方法
              callback.onResponse(OkHttpCall.this, response);
            } catch (Throwable t) {
              throwIfFatal(t);
              t.printStackTrace(); // TODO this is not great
            }
          }

          @Override
          public void onFailure(okhttp3.Call call, IOException e) {
            callFailure(e);
          }

          private void callFailure(Throwable e) {
            try {
              //回调callback.onFailure()方法
              callback.onFailure(OkHttpCall.this, e);
            } catch (Throwable t) {
              throwIfFatal(t);
              t.printStackTrace(); // TODO this is not great
            }
          }
        });
  }

  @Override
  public synchronized boolean isExecuted() {
    return executed;
  }

  @Override
  public Response<T> execute() throws IOException {
    okhttp3.Call call;

    synchronized (this) {
      //判断请求是否已经被执行，如果已被执行则抛出异常
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;
      //获取最原始的请求
      call = getRawCall();
    }

    if (canceled) {
      call.cancel();
    }
    //执行请求，并且解析响应，将okhttp3.response 转换成 retrofit2.response
    return parseResponse(call.execute());
  }

  /**
   * 构造原始请求
   * @return
   * @throws IOException
   */
  private okhttp3.Call createRawCall() throws IOException {
    okhttp3.Call call = callFactory.newCall(requestFactory.create(args));
    if (call == null) {
      throw new NullPointerException("Call.Factory returned null.");
    }
    return call;
  }

  /**
   * 解析响应，就是就okhttp3.response 转换成 retrofit2.response
   * @param rawResponse
   * @return
   * @throws IOException
   */
  Response<T> parseResponse(okhttp3.Response rawResponse) throws IOException {
    ResponseBody rawBody = rawResponse.body();

    // Remove the body's source (the only stateful object) so we can pass the response along.
    // 理解成删除body的状态，这样我们就可以传递Response了？？
    rawResponse =
        rawResponse
            .newBuilder()
            .body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength()))
            .build();

    int code = rawResponse.code();
    //请求失败
    if (code < 200 || code >= 300) {
      try {
        // Buffer the entire body to avoid future I/O.
        ResponseBody bufferedBody = Utils.buffer(rawBody);
        return Response.error(bufferedBody, rawResponse);
      } finally {
        rawBody.close();
      }
    }

    //请求成功
    if (code == 204 || code == 205) {
      rawBody.close();
      return Response.success(null, rawResponse);
    }

    //转换成我们期望的类型
    ExceptionCatchingResponseBody catchingBody = new ExceptionCatchingResponseBody(rawBody);
    try {
      T body = responseConverter.convert(catchingBody);
      return Response.success(body, rawResponse);
    } catch (RuntimeException e) {
      // If the underlying source threw an exception, propagate that rather than indicating it was
      // a runtime exception.
      catchingBody.throwIfCaught();
      throw e;
    }
  }

  @Override
  public void cancel() {
    canceled = true;

    okhttp3.Call call;
    synchronized (this) {
      call = rawCall;
    }
    if (call != null) {
      call.cancel();
    }
  }

  @Override
  public boolean isCanceled() {
    if (canceled) {
      return true;
    }
    synchronized (this) {
      return rawCall != null && rawCall.isCanceled();
    }
  }

  static final class NoContentResponseBody extends ResponseBody {
    private final @Nullable MediaType contentType;
    private final long contentLength;

    NoContentResponseBody(@Nullable MediaType contentType, long contentLength) {
      this.contentType = contentType;
      this.contentLength = contentLength;
    }

    @Override
    public MediaType contentType() {
      return contentType;
    }

    @Override
    public long contentLength() {
      return contentLength;
    }

    @Override
    public BufferedSource source() {
      throw new IllegalStateException("Cannot read raw response body of a converted body.");
    }
  }

  static final class ExceptionCatchingResponseBody extends ResponseBody {
    private final ResponseBody delegate;
    private final BufferedSource delegateSource;
    @Nullable IOException thrownException;

    ExceptionCatchingResponseBody(ResponseBody delegate) {
      this.delegate = delegate;
      this.delegateSource =
          Okio.buffer(
              new ForwardingSource(delegate.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                  try {
                    return super.read(sink, byteCount);
                  } catch (IOException e) {
                    thrownException = e;
                    throw e;
                  }
                }
              });
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public BufferedSource source() {
      return delegateSource;
    }

    @Override
    public void close() {
      delegate.close();
    }

    void throwIfCaught() throws IOException {
      if (thrownException != null) {
        throw thrownException;
      }
    }
  }
}
