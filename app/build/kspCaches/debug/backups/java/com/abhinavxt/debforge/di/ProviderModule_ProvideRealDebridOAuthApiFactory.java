package com.abhinavxt.debforge.di;

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
public final class ProviderModule_ProvideRealDebridOAuthApiFactory implements Factory<RealDebridOAuthApi> {
  private final Provider<OkHttpClient> baseProvider;

  private final Provider<Moshi> moshiProvider;

  private ProviderModule_ProvideRealDebridOAuthApiFactory(Provider<OkHttpClient> baseProvider,
      Provider<Moshi> moshiProvider) {
    this.baseProvider = baseProvider;
    this.moshiProvider = moshiProvider;
  }

  @Override
  public RealDebridOAuthApi get() {
    return provideRealDebridOAuthApi(baseProvider.get(), moshiProvider.get());
  }

  public static ProviderModule_ProvideRealDebridOAuthApiFactory create(
      Provider<OkHttpClient> baseProvider, Provider<Moshi> moshiProvider) {
    return new ProviderModule_ProvideRealDebridOAuthApiFactory(baseProvider, moshiProvider);
  }

  public static RealDebridOAuthApi provideRealDebridOAuthApi(OkHttpClient base, Moshi moshi) {
    return Preconditions.checkNotNullFromProvides(ProviderModule.INSTANCE.provideRealDebridOAuthApi(base, moshi));
  }
}
