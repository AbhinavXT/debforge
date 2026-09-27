package com.abhinavxt.debforge.ui;

import com.abhinavxt.debforge.data.repository.AuthRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class AuthGateViewModel_Factory implements Factory<AuthGateViewModel> {
  private final Provider<AuthRepository> authProvider;

  private AuthGateViewModel_Factory(Provider<AuthRepository> authProvider) {
    this.authProvider = authProvider;
  }

  @Override
  public AuthGateViewModel get() {
    return newInstance(authProvider.get());
  }

  public static AuthGateViewModel_Factory create(Provider<AuthRepository> authProvider) {
    return new AuthGateViewModel_Factory(authProvider);
  }

  public static AuthGateViewModel newInstance(AuthRepository auth) {
    return new AuthGateViewModel(auth);
  }
}
