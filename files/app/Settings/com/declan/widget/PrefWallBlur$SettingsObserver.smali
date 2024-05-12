# classes9.dex

.class public Lcom/declan/widget/PrefWallBlur$SettingsObserver;
.super Landroid/database/ContentObserver;
.source "PrefWallBlur.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lcom/declan/widget/PrefWallBlur;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x4
    name = "SettingsObserver"
.end annotation


# instance fields
.field final synthetic this$0:Lcom/declan/widget/PrefWallBlur;


# direct methods
.method public constructor <init>(Lcom/declan/widget/PrefWallBlur;Landroid/os/Handler;)V
    .registers 3

    .line 33
    iput-object p1, p0, Lcom/declan/widget/PrefWallBlur$SettingsObserver;->this$0:Lcom/declan/widget/PrefWallBlur;

    .line 34
    invoke-direct {p0, p2}, Landroid/database/ContentObserver;-><init>(Landroid/os/Handler;)V

    return-void
.end method


# virtual methods
.method public DeclanBlurKey()V
    .registers 4

    .line 38
    iget-object v0, p0, Lcom/declan/widget/PrefWallBlur$SettingsObserver;->this$0:Lcom/declan/widget/PrefWallBlur;

    invoke-virtual {v0}, Lcom/declan/widget/PrefWallBlur;->getContext()Landroid/content/Context;

    move-result-object v0

    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    const-string v1, "declan_blur"

    .line 39
    invoke-static {v1}, Landroid/provider/Settings$System;->getUriFor(Ljava/lang/String;)Landroid/net/Uri;

    move-result-object v1

    const/4 v2, 0x0

    invoke-virtual {v0, v1, v2, p0}, Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V

    return-void
.end method

.method public onChange(Z)V
    .registers 2

    .line 43
    iget-object p1, p0, Lcom/declan/widget/PrefWallBlur$SettingsObserver;->this$0:Lcom/declan/widget/PrefWallBlur;

    invoke-static {p1}, Lcom/declan/widget/PrefWallBlur;->access$000(Lcom/declan/widget/PrefWallBlur;)V

    return-void
.end method
