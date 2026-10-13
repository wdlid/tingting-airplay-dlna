/*
 * 内嵌最小 alsa.conf：Android 4.4 上没有 /usr/share/alsa/alsa.conf，
 * 启动时把配置写到可写目录并通过 ALSA_CONFIG_PATH 指给 alsa-lib，
 * 这样单个二进制即可直接用 "hw:1,0" 打开声卡。
 * 若用户已自行设置 ALSA_CONFIG_PATH 则不覆盖。
 */
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char embedded_alsa_conf_text[] =
    "pcm.hw {\n"
    "  @args [ CARD DEV SUBDEV ]\n"
    "  @args.CARD { type string default \"0\" }\n"
    "  @args.DEV { type integer default 0 }\n"
    "  @args.SUBDEV { type integer default -1 }\n"
    "  type hw\n"
    "  card $CARD\n"
    "  device $DEV\n"
    "  subdevice $SUBDEV\n"
    "}\n"
    "pcm.plughw {\n"
    "  @args [ CARD DEV SUBDEV ]\n"
    "  @args.CARD { type string default \"0\" }\n"
    "  @args.DEV { type integer default 0 }\n"
    "  @args.SUBDEV { type integer default -1 }\n"
    "  type plug\n"
    "  slave.pcm {\n"
    "    type hw\n"
    "    card $CARD\n"
    "    device $DEV\n"
    "    subdevice $SUBDEV\n"
    "  }\n"
    "}\n"
    /* 此声卡实际以 48kHz 运行(系统 HAL 的 pcm_config 即 48000), 驱动却接受 44100 参数,
       导致播放速度快约 9%。sp48 让 alsa-lib 的 rate 插件把 44.1k 转成 48k 再送给硬件 */
    "pcm.sp48 {\n"
    "  type plug\n"
    "  slave { pcm { type hw card 1 device 0 } rate 48000 format S16_LE channels 2 }\n"
    "}\n"
    "pcm.!default { type plug slave.pcm { type hw card 0 device 0 } }\n"
    "ctl.hw {\n"
    "  @args [ CARD ]\n"
    "  @args.CARD { type string default \"0\" }\n"
    "  type hw\n"
    "  card $CARD\n"
    "}\n"
    "ctl.!default { type hw card 0 }\n";

/* 调试用: 崩溃时打印寄存器 (pc/lr/fault addr), 配合 addr2line 定位 */
#include <signal.h>
#include <stdio.h>
#include <ucontext.h>
static void crash_handler(int sig, siginfo_t *si, void *ctx) {
  ucontext_t *uc = (ucontext_t *)ctx;
  unsigned long *r = (unsigned long *)&uc->uc_mcontext;
  char buf[256];
  int n = snprintf(buf, sizeof(buf),
                   "\nCRASH sig=%d addr=%p pc=%08lx lr=%08lx sp=%08lx r0=%08lx r1=%08lx r2=%08lx r3=%08lx\n",
                   sig, si->si_addr, r[18], r[17], r[16], r[3], r[4], r[5], r[6]);
  write(2, buf, n);
  _exit(139);
}
static void install_crash_handler(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = crash_handler;
  sa.sa_flags = SA_SIGINFO;
  sigaction(SIGSEGV, &sa, NULL);
  sigaction(SIGBUS, &sa, NULL);
  sigaction(SIGILL, &sa, NULL);
  // Android 4.4 的 adb shell 退出时会给子进程发 SIGHUP, 终端断开后写日志会 SIGPIPE,
  // 忽略它们才能脱离 adb 常驻后台
  signal(SIGHUP, SIG_IGN);
  signal(SIGPIPE, SIG_IGN);
}

static void embedded_alsa_conf(void) {
  install_crash_handler();
  // SP_DAEMON=1 时自行后台化 (Android 4.4 没有 nohup/setsid, adb shell 退出会杀掉子进程)
  // 日志写到 $SP_LOG, 默认 /data/local/tmp/sp.log
  if (getenv("SP_DAEMON") != NULL) {
    const char *logp = getenv("SP_LOG") ? getenv("SP_LOG") : "/data/local/tmp/sp.log";
    if (fork() > 0)
      _exit(0);
    setsid();
    if (fork() > 0)
      _exit(0);
    int lfd = open(logp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    int nfd = open("/dev/null", O_RDONLY);
    if (nfd >= 0)
      dup2(nfd, 0);
    if (lfd >= 0) {
      dup2(lfd, 1);
      dup2(lfd, 2);
    }
  }
  if (getenv("ALSA_CONFIG_PATH") != NULL)
    return;
  const char *dirs[] = {getenv("TMPDIR"), "/data/local/tmp", "/tmp", "/sdcard", "."};
  for (int i = 0; i < 5; i++) {
    if (dirs[i] == NULL)
      continue;
    char path[512];
    strncpy(path, dirs[i], sizeof(path) - 40);
    path[sizeof(path) - 40] = '\0';
    strcat(path, "/.shairport-alsa.conf");
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0)
      continue;
    size_t len = sizeof(embedded_alsa_conf_text) - 1;
    ssize_t w = write(fd, embedded_alsa_conf_text, len);
    close(fd);
    if (w == (ssize_t)len) {
      setenv("ALSA_CONFIG_PATH", path, 0);
      return;
    }
  }
}
