package com.abhinavxt.debforge.data.prefs;

import android.content.Context;
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
public final class TokenStore_Factory implements Factory<TokenStore> {
  private final Provider<Context> contextProvider;

  private TokenStore_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public TokenStore get() {
    return newInstance(contextProvider.get());
  }

  public static TokenStore_Factory create(Provider<Context> contextProvider) {
    return new TokenStore_Factory(contextProvider);
  }

  public static TokenStore newInstance(Context context) {
    return new TokenStore(context);
  }
}
