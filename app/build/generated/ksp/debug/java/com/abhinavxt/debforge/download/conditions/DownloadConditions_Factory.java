package com.abhinavxt.debforge.download.conditions;

import com.abhinavxt.debforge.data.prefs.SettingsStore;
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
public final class DownloadConditions_Factory implements Factory<DownloadConditions> {
  private final Provider<SettingsStore> settingsProvider;

  private final Provider<NetworkMonitor> networkProvider;

  private final Provider<PowerMonitor> powerProvider;

  private DownloadConditions_Factory(Provider<SettingsStore> settingsProvider,
      Provider<NetworkMonitor> networkProvider, Provider<PowerMonitor> powerProvider) {
    this.settingsProvider = settingsProvider;
    this.networkProvider = networkProvider;
    this.powerProvider = powerProvider;
  }

  @Override
  public DownloadConditions get() {
    return newInstance(settingsProvider.get(), networkProvider.get(), powerProvider.get());
  }

  public static DownloadConditions_Factory create(Provider<SettingsStore> settingsProvider,
      Provider<NetworkMonitor> networkProvider, Provider<PowerMonitor> powerProvider) {
    return new DownloadConditions_Factory(settingsProvider, networkProvider, powerProvider);
  }

  public static DownloadConditions newInstance(SettingsStore settings, NetworkMonitor network,
      PowerMonitor power) {
    return new DownloadConditions(settings, network, power);
  }
}
