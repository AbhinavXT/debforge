package com.abhinavxt.debforge.data.repository;

import com.abhinavxt.debforge.data.provider.ProviderRegistry;
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
public final class DownloadsRepository_Factory implements Factory<DownloadsRepository> {
  private final Provider<ProviderRegistry> registryProvider;

  private DownloadsRepository_Factory(Provider<ProviderRegistry> registryProvider) {
    this.registryProvider = registryProvider;
  }

  @Override
  public DownloadsRepository get() {
    return newInstance(registryProvider.get());
  }

  public static DownloadsRepository_Factory create(Provider<ProviderRegistry> registryProvider) {
    return new DownloadsRepository_Factory(registryProvider);
  }

  public static DownloadsRepository newInstance(ProviderRegistry registry) {
    return new DownloadsRepository(registry);
  }
}
