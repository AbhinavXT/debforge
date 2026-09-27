package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridApi;
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridOAuthApi;
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
public final class ProviderModule_ProvideRealDebridApiFactory implements Factory<RealDebridApi> {
  private final Provider<OkHttpClient> baseProvider;

  private final Provider<Moshi> moshiProvider;

  private final Provider<TokenStore> tokenStoreProvider;

  private final Provider<RealDebridOAuthApi> oauthApiProvider;

  private ProviderModule_ProvideRealDebridApiFactory(Provider<OkHttpClient> baseProvider,
      Provider<Moshi> moshiProvider, Provider<TokenStore> tokenStoreProvider,
      Provider<RealDebridOAuthApi> oauthApiProvider) {
    this.baseProvider = baseProvider;
    this.moshiProvider = moshiProvider;
    this.tokenStoreProvider = tokenStoreProvider;
    this.oauthApiProvider = oauthApiProvider;
  }

  @Override
  public RealDebridApi get() {
    return provideRealDebridApi(baseProvider.get(), moshiProvider.get(), tokenStoreProvider.get(), oauthApiProvider.get());
  }

  public static ProviderModule_ProvideRealDebridApiFactory create(
      Provider<OkHttpClient> baseProvider, Provider<Moshi> moshiProvider,
      Provider<TokenStore> tokenStoreProvider, Provider<RealDebridOAuthApi> oauthApiProvider) {
    return new ProviderModule_ProvideRealDebridApiFactory(baseProvider, moshiProvider, tokenStoreProvider, oauthApiProvider);
  }

  public static RealDebridApi provideRealDebridApi(OkHttpClient base, Moshi moshi,
      TokenStore tokenStore, RealDebridOAuthApi oauthApi) {
    return Preconditions.checkNotNullFromProvides(ProviderModule.INSTANCE.provideRealDebridApi(base, moshi, tokenStore, oauthApi));
  }
}
