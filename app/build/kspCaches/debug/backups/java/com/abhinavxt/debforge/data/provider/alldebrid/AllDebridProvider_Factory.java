package com.abhinavxt.debforge.data.provider.alldebrid;

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
public final class AllDebridProvider_Factory implements Factory<AllDebridProvider> {
  private final Provider<AllDebridApi> apiProvider;

  private AllDebridProvider_Factory(Provider<AllDebridApi> apiProvider) {
    this.apiProvider = apiProvider;
  }

  @Override
  public AllDebridProvider get() {
    return newInstance(apiProvider.get());
  }

  public static AllDebridProvider_Factory create(Provider<AllDebridApi> apiProvider) {
    return new AllDebridProvider_Factory(apiProvider);
  }

  public static AllDebridProvider newInstance(AllDebridApi api) {
    return new AllDebridProvider(api);
  }
}
