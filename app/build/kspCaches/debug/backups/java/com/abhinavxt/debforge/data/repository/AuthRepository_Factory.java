package com.abhinavxt.debforge.data.repository;

import com.abhinavxt.debforge.data.prefs.TokenStore;
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
public final class AuthRepository_Factory implements Factory<AuthRepository> {
  private final Provider<ProviderRegistry> registryProvider;

  private final Provider<TokenStore> tokenStoreProvider;

  private AuthRepository_Factory(Provider<ProviderRegistry> registryProvider,
      Provider<TokenStore> tokenStoreProvider) {
    this.registryProvider = registryProvider;
    this.tokenStoreProvider = tokenStoreProvider;
  }

  @Override
  public AuthRepository get() {
    return newInstance(registryProvider.get(), tokenStoreProvider.get());
  }

  public static AuthRepository_Factory create(Provider<ProviderRegistry> registryProvider,
      Provider<TokenStore> tokenStoreProvider) {
    return new AuthRepository_Factory(registryProvider, tokenStoreProvider);
  }

  public static AuthRepository newInstance(ProviderRegistry registry, TokenStore tokenStore) {
    return new AuthRepository(registry, tokenStore);
  }
}
