package com.abhinavxt.debforge;

import com.abhinavxt.debforge.download.DownloadController;
import com.abhinavxt.debforge.download.DownloadScheduler;
import com.abhinavxt.debforge.download.Housekeeper;
import com.abhinavxt.debforge.ui.add.PendingAddStore;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<PendingAddStore> pendingAddsProvider;

  private final Provider<DownloadScheduler> downloadSchedulerProvider;

  private final Provider<DownloadController> downloadControllerProvider;

  private final Provider<Housekeeper> housekeeperProvider;

  private MainActivity_MembersInjector(Provider<PendingAddStore> pendingAddsProvider,
      Provider<DownloadScheduler> downloadSchedulerProvider,
      Provider<DownloadController> downloadControllerProvider,
      Provider<Housekeeper> housekeeperProvider) {
    this.pendingAddsProvider = pendingAddsProvider;
    this.downloadSchedulerProvider = downloadSchedulerProvider;
    this.downloadControllerProvider = downloadControllerProvider;
    this.housekeeperProvider = housekeeperProvider;
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectPendingAdds(instance, pendingAddsProvider.get());
    injectDownloadScheduler(instance, downloadSchedulerProvider.get());
    injectDownloadController(instance, downloadControllerProvider.get());
    injectHousekeeper(instance, housekeeperProvider.get());
  }

  public static MembersInjector<MainActivity> create(Provider<PendingAddStore> pendingAddsProvider,
      Provider<DownloadScheduler> downloadSchedulerProvider,
      Provider<DownloadController> downloadControllerProvider,
      Provider<Housekeeper> housekeeperProvider) {
    return new MainActivity_MembersInjector(pendingAddsProvider, downloadSchedulerProvider, downloadControllerProvider, housekeeperProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.MainActivity.pendingAdds")
  public static void injectPendingAdds(MainActivity instance, PendingAddStore pendingAdds) {
    instance.pendingAdds = pendingAdds;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.MainActivity.downloadScheduler")
  public static void injectDownloadScheduler(MainActivity instance,
      DownloadScheduler downloadScheduler) {
    instance.downloadScheduler = downloadScheduler;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.MainActivity.downloadController")
  public static void injectDownloadController(MainActivity instance,
      DownloadController downloadController) {
    instance.downloadController = downloadController;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.MainActivity.housekeeper")
  public static void injectHousekeeper(MainActivity instance, Housekeeper housekeeper) {
    instance.housekeeper = housekeeper;
  }
}
