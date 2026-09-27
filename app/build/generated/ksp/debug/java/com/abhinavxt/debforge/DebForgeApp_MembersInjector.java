package com.abhinavxt.debforge;

import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.abhinavxt.debforge.widget.WidgetSync;
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
public final class DebForgeApp_MembersInjector implements MembersInjector<DebForgeApp> {
  private final Provider<TokenStore> tokenStoreProvider;

  private final Provider<WidgetSync> widgetSyncProvider;

  private final Provider<FollowStore> followStoreProvider;

  private DebForgeApp_MembersInjector(Provider<TokenStore> tokenStoreProvider,
      Provider<WidgetSync> widgetSyncProvider, Provider<FollowStore> followStoreProvider) {
    this.tokenStoreProvider = tokenStoreProvider;
    this.widgetSyncProvider = widgetSyncProvider;
    this.followStoreProvider = followStoreProvider;
  }

  @Override
  public void injectMembers(DebForgeApp instance) {
    injectTokenStore(instance, tokenStoreProvider.get());
    injectWidgetSync(instance, widgetSyncProvider.get());
    injectFollowStore(instance, followStoreProvider.get());
  }

  public static MembersInjector<DebForgeApp> create(Provider<TokenStore> tokenStoreProvider,
      Provider<WidgetSync> widgetSyncProvider, Provider<FollowStore> followStoreProvider) {
    return new DebForgeApp_MembersInjector(tokenStoreProvider, widgetSyncProvider, followStoreProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.DebForgeApp.tokenStore")
  public static void injectTokenStore(DebForgeApp instance, TokenStore tokenStore) {
    instance.tokenStore = tokenStore;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.DebForgeApp.widgetSync")
  public static void injectWidgetSync(DebForgeApp instance, WidgetSync widgetSync) {
    instance.widgetSync = widgetSync;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.DebForgeApp.followStore")
  public static void injectFollowStore(DebForgeApp instance, FollowStore followStore) {
    instance.followStore = followStore;
  }
}
