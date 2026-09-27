package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.abhinavxt.debforge.data.provider.premiumize.PremiumizeApi;
import com.squareup.moshi.Moshi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
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
public final class ProviderModule_ProvidePremiumizeApiFactory implements Factory<PremiumizeApi> {
  private final Provider<OkHttpClient> baseProvider;

  private final Provider<Moshi> moshiProvider;

  private final Provider<TokenStore> tokenStoreProvider;

  private ProviderModule_ProvidePremiumizeApiFactory(Provider<OkHttpClient> baseProvider,
      Provider<Moshi> moshiProvider, Provider<TokenStore> tokenStoreProvider) {
    this.baseProvider = baseProvider;
    this.moshiProvider = moshiProvider;
    this.tokenStoreProvider = tokenStoreProvider;
  }

  @Override
  public PremiumizeApi get() {
    return providePremiumizeApi(baseProvider.get(), moshiProvider.get(), tokenStoreProvider.get());
  }

  public static ProviderModule_ProvidePremiumizeApiFactory create(
      Provider<OkHttpClient> baseProvider, Provider<Moshi> moshiProvider,
      Provider<TokenStore> tokenStoreProvider) {
    return new ProviderModule_ProvidePremiumizeApiFactory(baseProvider, moshiProvider, tokenStoreProvider);
  }

  public static PremiumizeApi providePremiumizeApi(OkHttpClient base, Moshi moshi,
      TokenStore tokenStore) {
    return Preconditions.checkNotNullFromProvides(ProviderModule.INSTANCE.providePremiumizeApi(base, moshi, tokenStore));
  }
}
