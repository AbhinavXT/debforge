package com.abhinavxt.debforge.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.abhinavxt.debforge.di.DownloadHttpClient")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class NetworkModule_ProvideDownloadOkHttpFactory implements Factory<OkHttpClient> {
  @Override
  public OkHttpClient get() {
    return provideDownloadOkHttp();
  }

  public static NetworkModule_ProvideDownloadOkHttpFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static OkHttpClient provideDownloadOkHttp() {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideDownloadOkHttp());
  }

  private static final class InstanceHolder {
    static final NetworkModule_ProvideDownloadOkHttpFactory INSTANCE = new NetworkModule_ProvideDownloadOkHttpFactory();
  }
}
