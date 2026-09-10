.class public Lcom/pancakeify/stable/PancakeBootstrap;
.super Ljava/lang/Object;

.field private static started:Z


.method static constructor <clinit>()V
    .registers 1
    const/4 v0, 0x0
    sput-boolean v0, Lcom/pancakeify/stable/PancakeBootstrap;->started:Z
    return-void
.end method

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static declared-synchronized start(Landroid/app/Application;Landroid/content/Context;)V
    .registers 4
    const-class v0, Lcom/pancakeify/stable/PancakeBootstrap;
    monitor-enter v0
    :try_start
    sget-boolean v1, Lcom/pancakeify/stable/PancakeBootstrap;->started:Z
    if-eqz v1, :cond_go
    monitor-exit v0
    return-void
    :cond_go
    const/4 v1, 0x1
    sput-boolean v1, Lcom/pancakeify/stable/PancakeBootstrap;->started:Z
    const-string v1, "Pancakeify"
    const-string v2, "🥞 Pancakeify alive: bootstrap injected into host attachBaseContext"
    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    monitor-exit v0
    return-void
    :try_end
    .catchall {:try_start .. :try_end} :catchall
    :catchall
    move-exception v1
    monitor-exit v0
    throw v1
.end method
