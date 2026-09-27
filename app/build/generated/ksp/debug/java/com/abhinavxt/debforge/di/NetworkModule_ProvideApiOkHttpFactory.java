package com.abhinavxt.debforge.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.abhinavxt.debforge.di.ApiHttpClient")
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
public final class NetworkModule_ProvideApiOkHttpFactory implements Factory<OkHttpClient> {
  @Override
  public OkHttpClient get() {
    return provideApiOkHttp();
  }

  public static NetworkModule_ProvideApiOkHttpFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static OkHttpClient provideApiOkHttp() {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideApiOkHttp());
  }

  private static final class InstanceHolder {
    static final NetworkModule_ProvideApiOkHttpFactory INSTANCE = new NetworkModule_ProvideApiOkHttpFactory();
  }
}
