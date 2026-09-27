package com.abhinavxt.debforge.data.provider.torbox;

import com.abhinavxt.debforge.data.prefs.TokenStore;
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
public final class TorBoxProvider_Factory implements Factory<TorBoxProvider> {
  private final Provider<TorBoxApi> apiProvider;

  private final Provider<TokenStore> tokenStoreProvider;

  private TorBoxProvider_Factory(Provider<TorBoxApi> apiProvider,
      Provider<TokenStore> tokenStoreProvider) {
    this.apiProvider = apiProvider;
    this.tokenStoreProvider = tokenStoreProvider;
  }

  @Override
  public TorBoxProvider get() {
    return newInstance(apiProvider.get(), tokenStoreProvider.get());
  }

  public static TorBoxProvider_Factory create(Provider<TorBoxApi> apiProvider,
      Provider<TokenStore> tokenStoreProvider) {
    return new TorBoxProvider_Factory(apiProvider, tokenStoreProvider);
  }

  public static TorBoxProvider newInstance(TorBoxApi api, TokenStore tokenStore) {
    return new TorBoxProvider(api, tokenStore);
  }
}
