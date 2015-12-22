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
package retrofit2.mock;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class BehaviorCall<T> implements Call<T> {
  private final NetworkBehavior behavior;
  private final ExecutorService backgroundExecutor;
  private final Call<T> delegate;

  private volatile Future<?> task;
  private volatile boolean canceled;
  private volatile boolean executed;

  BehaviorCall(NetworkBehavior behavior, ExecutorService backgroundExecutor, Call<T> delegate) {
    this.behavior = behavior;
    this.backgroundExecutor = backgroundExecutor;
    this.delegate = delegate;
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public Call<T> clone() {
    return new BehaviorCall<>(behavior, backgroundExecutor, delegate.clone());
  }

  @Override public void enqueue(final Callback<T> callback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already executed");
      executed = true;
    }
    task = backgroundExecutor.submit(new Runnable() {
      private boolean delaySleep() {
        long sleepMs = behavior.calculateDelay(MILLISECONDS);
        if (sleepMs > 0) {
          try {
            Thread.sleep(sleepMs);
          } catch (InterruptedException e) {
            callback.onFailure(new IOException("canceled"));
            return false;
          }
        }
        return true;
      }

      @Override public void run() {
        if (canceled) {
          callback.onFailure(new IOException("canceled"));
        } else if (behavior.calculateIsFailure()) {
          if (delaySleep()) {
            callback.onFailure(behavior.failureException());
          }
        } else {
          delegate.enqueue(new Callback<T>() {
            @Override public void onResponse(final Response<T> response) {
              if (delaySleep()) {
                callback.onResponse(response);
              }
            }

            @Override public void onFailure(final Throwable t) {
              if (delaySleep()) {
                callback.onFailure(t);
              }
            }
          });
        }
      }
    });
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public Response<T> execute() throws IOException {
    final AtomicReference<Response<T>> responseRef = new AtomicReference<>();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    enqueue(new Callback<T>() {
      @Override public void onResponse(Response<T> response) {
        responseRef.set(response);
        latch.countDown();
      }

      @Override public void onFailure(Throwable t) {
        failureRef.set(t);
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new IOException("canceled");
    }
    Response<T> response = responseRef.get();
    if (response != null) return response;
    Throwable failure = failureRef.get();
    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
    if (failure instanceof IOException) throw (IOException) failure;
    throw new RuntimeException(failure);
  }

  @Override public void cancel() {
    canceled = true;
    Future<?> task = this.task;
    if (task != null) {
      task.cancel(true);
    }
  }

  @Override public boolean isCanceled() {
    return canceled;
  }
}