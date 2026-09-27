package com.abhinavxt.debforge.data.provider.premiumize;

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
public final class PremiumizeProvider_Factory implements Factory<PremiumizeProvider> {
  private final Provider<PremiumizeApi> apiProvider;

  private PremiumizeProvider_Factory(Provider<PremiumizeApi> apiProvider) {
    this.apiProvider = apiProvider;
  }

  @Override
  public PremiumizeProvider get() {
    return newInstance(apiProvider.get());
  }

  public static PremiumizeProvider_Factory create(Provider<PremiumizeApi> apiProvider) {
    return new PremiumizeProvider_Factory(apiProvider);
  }

  public static PremiumizeProvider newInstance(PremiumizeApi api) {
    return new PremiumizeProvider(api);
  }
}
