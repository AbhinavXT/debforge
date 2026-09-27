package com.abhinavxt.debforge.download.conditions;

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
public final class RateLimiter_Factory implements Factory<RateLimiter> {
  private final Provider<DownloadConditions> conditionsProvider;

  private RateLimiter_Factory(Provider<DownloadConditions> conditionsProvider) {
    this.conditionsProvider = conditionsProvider;
  }

  @Override
  public RateLimiter get() {
    return newInstance(conditionsProvider.get());
  }

  public static RateLimiter_Factory create(Provider<DownloadConditions> conditionsProvider) {
    return new RateLimiter_Factory(conditionsProvider);
  }

  public static RateLimiter newInstance(DownloadConditions conditions) {
    return new RateLimiter(conditions);
  }
}
