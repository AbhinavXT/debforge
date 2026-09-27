package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.local.DebForgeDatabase;
import com.abhinavxt.debforge.download.watch.WatchDao;
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
public final class DatabaseModule_ProvideWatchDaoFactory implements Factory<WatchDao> {
  private final Provider<DebForgeDatabase> dbProvider;

  private DatabaseModule_ProvideWatchDaoFactory(Provider<DebForgeDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public WatchDao get() {
    return provideWatchDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideWatchDaoFactory create(
      Provider<DebForgeDatabase> dbProvider) {
    return new DatabaseModule_ProvideWatchDaoFactory(dbProvider);
  }

  public static WatchDao provideWatchDao(DebForgeDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideWatchDao(db));
  }
}
