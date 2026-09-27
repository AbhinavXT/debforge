package com.abhinavxt.debforge.data.provider;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import java.util.Set;
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
public final class ProviderRegistry_Factory implements Factory<ProviderRegistry> {
  private final Provider<Set<DebridProvider>> providersProvider;

  private ProviderRegistry_Factory(Provider<Set<DebridProvider>> providersProvider) {
    this.providersProvider = providersProvider;
  }

  @Override
  public ProviderRegistry get() {
    return newInstance(providersProvider.get());
  }

  public static ProviderRegistry_Factory create(Provider<Set<DebridProvider>> providersProvider) {
    return new ProviderRegistry_Factory(providersProvider);
  }

  public static ProviderRegistry newInstance(Set<DebridProvider> providers) {
    return new ProviderRegistry(providers);
  }
}
