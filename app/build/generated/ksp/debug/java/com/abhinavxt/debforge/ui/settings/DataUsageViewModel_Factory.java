package com.abhinavxt.debforge.ui.settings;

import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.data.usage.UsageDao;
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
public final class DataUsageViewModel_Factory implements Factory<DataUsageViewModel> {
  private final Provider<UsageDao> daoProvider;

  private final Provider<AuthRepository> authProvider;

  private DataUsageViewModel_Factory(Provider<UsageDao> daoProvider,
      Provider<AuthRepository> authProvider) {
    this.daoProvider = daoProvider;
    this.authProvider = authProvider;
  }

  @Override
  public DataUsageViewModel get() {
    return newInstance(daoProvider.get(), authProvider.get());
  }

  public static DataUsageViewModel_Factory create(Provider<UsageDao> daoProvider,
      Provider<AuthRepository> authProvider) {
    return new DataUsageViewModel_Factory(daoProvider, authProvider);
  }

  public static DataUsageViewModel newInstance(UsageDao dao, AuthRepository auth) {
    return new DataUsageViewModel(dao, auth);
  }
}
