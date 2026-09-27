package com.abhinavxt.debforge.ui.setup;

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
public final class SetupViewModel_Factory implements Factory<SetupViewModel> {
  private final Provider<AuthRepository> authProvider;

  private SetupViewModel_Factory(Provider<AuthRepository> authProvider) {
    this.authProvider = authProvider;
  }

  @Override
  public SetupViewModel get() {
    return newInstance(authProvider.get());
  }

  public static SetupViewModel_Factory create(Provider<AuthRepository> authProvider) {
    return new SetupViewModel_Factory(authProvider);
  }

  public static SetupViewModel newInstance(AuthRepository auth) {
    return new SetupViewModel(auth);
  }
}
