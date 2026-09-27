package com.abhinavxt.debforge.ui.settings;

import android.content.Context;
import com.abhinavxt.debforge.data.backup.BackupManager;
import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.download.DownloadScheduler;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class BackupViewModel_Factory implements Factory<BackupViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<BackupManager> backupProvider;

  private final Provider<AuthRepository> authProvider;

  private final Provider<DownloadScheduler> schedulerProvider;

  private BackupViewModel_Factory(Provider<Context> contextProvider,
      Provider<BackupManager> backupProvider, Provider<AuthRepository> authProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    this.contextProvider = contextProvider;
    this.backupProvider = backupProvider;
    this.authProvider = authProvider;
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public BackupViewModel get() {
    return newInstance(contextProvider.get(), backupProvider.get(), authProvider.get(), schedulerProvider.get());
  }

  public static BackupViewModel_Factory create(Provider<Context> contextProvider,
      Provider<BackupManager> backupProvider, Provider<AuthRepository> authProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    return new BackupViewModel_Factory(contextProvider, backupProvider, authProvider, schedulerProvider);
  }

  public static BackupViewModel newInstance(Context context, BackupManager backup,
      AuthRepository auth, DownloadScheduler scheduler) {
    return new BackupViewModel(context, backup, auth, scheduler);
  }
}
