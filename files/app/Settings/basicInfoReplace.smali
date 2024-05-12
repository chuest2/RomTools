    .registers 3

    .line 148
    iget-boolean v0, p0, Lcom/android/settings/device/DeviceBasicInfoPresenter;->isUseMiui15CardStyle:Z

    const/4 v1, 0x2

    if-eqz v0, :cond_e

    goto :goto_d

    :cond_c
    const/4 v1, 0x1

    :goto_d
    return v1

    :cond_e
    iget-object p0, p0, Lcom/android/settings/device/DeviceBasicInfoPresenter;->mContext:Landroid/content/Context;

    invoke-static {p0}, Lcom/android/settings/MiuiUtils;->isLandScape(Landroid/content/Context;)Z

    move-result p0

    if-eqz p0, :cond_1d

    invoke-static {}, Lcom/android/settings/utils/SettingsFeatures;->isSplitTabletDevice()Z

    move-result p0

    if-eqz p0, :cond_1d

    const/4 v1, 0x3

    :cond_1d
    return v1