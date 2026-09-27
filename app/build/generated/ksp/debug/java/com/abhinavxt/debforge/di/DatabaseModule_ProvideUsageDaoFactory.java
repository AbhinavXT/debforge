package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.local.DebForgeDatabase;
import com.abhinavxt.debforge.data.usage.UsageDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class DatabaseModule_ProvideUsageDaoFactory implements Factory<UsageDao> {
  private final Provider<DebForgeDatabase> dbProvider;

  private DatabaseModule_ProvideUsageDaoFactory(Provider<DebForgeDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public UsageDao get() {
    return provideUsageDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideUsageDaoFactory create(
      Provider<DebForgeDatabase> dbProvider) {
    return new DatabaseModule_ProvideUsageDaoFactory(dbProvider);
  }

  public static UsageDao provideUsageDao(DebForgeDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideUsageDao(db));
  }
}
