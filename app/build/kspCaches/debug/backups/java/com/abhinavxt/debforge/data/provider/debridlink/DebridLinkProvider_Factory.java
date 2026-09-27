package com.abhinavxt.debforge.data.provider.debridlink;

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
public final class DebridLinkProvider_Factory implements Factory<DebridLinkProvider> {
  private final Provider<DebridLinkApi> apiProvider;

  private DebridLinkProvider_Factory(Provider<DebridLinkApi> apiProvider) {
    this.apiProvider = apiProvider;
  }

  @Override
  public DebridLinkProvider get() {
    return newInstance(apiProvider.get());
  }

  public static DebridLinkProvider_Factory create(Provider<DebridLinkApi> apiProvider) {
    return new DebridLinkProvider_Factory(apiProvider);
  }

  public static DebridLinkProvider newInstance(DebridLinkApi api) {
    return new DebridLinkProvider(api);
  }
}
