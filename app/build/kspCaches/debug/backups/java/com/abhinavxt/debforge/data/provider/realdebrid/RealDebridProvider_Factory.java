package com.abhinavxt.debforge.data.provider.realdebrid;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
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
public final class RealDebridProvider_Factory implements Factory<RealDebridProvider> {
  private final Provider<RealDebridApi> apiProvider;

  private final Provider<RealDebridOAuthApi> oauthApiProvider;

  private RealDebridProvider_Factory(Provider<RealDebridApi> apiProvider,
      Provider<RealDebridOAuthApi> oauthApiProvider) {
    this.apiProvider = apiProvider;
    this.oauthApiProvider = oauthApiProvider;
  }

  @Override
  public RealDebridProvider get() {
    return newInstance(apiProvider.get(), oauthApiProvider.get());
  }

  public static RealDebridProvider_Factory create(Provider<RealDebridApi> apiProvider,
      Provider<RealDebridOAuthApi> oauthApiProvider) {
    return new RealDebridProvider_Factory(apiProvider, oauthApiProvider);
  }

  public static RealDebridProvider newInstance(RealDebridApi api, RealDebridOAuthApi oauthApi) {
    return new RealDebridProvider(api, oauthApi);
  }
}
