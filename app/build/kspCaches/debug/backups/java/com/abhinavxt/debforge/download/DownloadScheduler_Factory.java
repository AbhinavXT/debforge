package com.abhinavxt.debforge.download;

import android.content.Context;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.download.conditions.DownloadConditions;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class DownloadScheduler_Factory implements Factory<DownloadScheduler> {
  private final Provider<Context> contextProvider;

  private final Provider<DownloadQueueRunner> runnerProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<DownloadConditions> conditionsProvider;

  private DownloadScheduler_Factory(Provider<Context> contextProvider,
      Provider<DownloadQueueRunner> runnerProvider, Provider<DownloadDao> downloadDaoProvider,
      Provider<DownloadConditions> conditionsProvider) {
    this.contextProvider = contextProvider;
    this.runnerProvider = runnerProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.conditionsProvider = conditionsProvider;
  }

  @Override
  public DownloadScheduler get() {
    return newInstance(contextProvider.get(), runnerProvider.get(), downloadDaoProvider.get(), conditionsProvider.get());
  }

  public static DownloadScheduler_Factory create(Provider<Context> contextProvider,
      Provider<DownloadQueueRunner> runnerProvider, Provider<DownloadDao> downloadDaoProvider,
      Provider<DownloadConditions> conditionsProvider) {
    return new DownloadScheduler_Factory(contextProvider, runnerProvider, downloadDaoProvider, conditionsProvider);
  }

  public static DownloadScheduler newInstance(Context context, DownloadQueueRunner runner,
      DownloadDao downloadDao, DownloadConditions conditions) {
    return new DownloadScheduler(context, runner, downloadDao, conditions);
  }
}
