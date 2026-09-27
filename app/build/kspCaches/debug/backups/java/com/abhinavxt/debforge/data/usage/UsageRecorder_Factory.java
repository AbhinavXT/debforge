package com.abhinavxt.debforge.data.usage;

import com.abhinavxt.debforge.download.NetworkMonitor;
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
public final class UsageRecorder_Factory implements Factory<UsageRecorder> {
  private final Provider<UsageDao> daoProvider;

  private final Provider<NetworkMonitor> networkProvider;

  private UsageRecorder_Factory(Provider<UsageDao> daoProvider,
      Provider<NetworkMonitor> networkProvider) {
    this.daoProvider = daoProvider;
    this.networkProvider = networkProvider;
  }

  @Override
  public UsageRecorder get() {
    return newInstance(daoProvider.get(), networkProvider.get());
  }

  public static UsageRecorder_Factory create(Provider<UsageDao> daoProvider,
      Provider<NetworkMonitor> networkProvider) {
    return new UsageRecorder_Factory(daoProvider, networkProvider);
  }

  public static UsageRecorder newInstance(UsageDao dao, NetworkMonitor network) {
    return new UsageRecorder(dao, network);
  }
}
