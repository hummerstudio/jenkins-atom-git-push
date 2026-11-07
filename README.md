# jenkins-atom-git-push

把 workspace 里的提交推到远端，并**确认远端真的动了**。

git-push 插件是一堆勾选框套 `git push`。值得小心的是凭据：token 写进 URL 会出现在 `ps`、临时 remote 的
配置和 git 的错误输出里。所以这里**用户名进 URL，密码走 `GIT_ASKPASS`**，用完即删。

推完还要证明：`git ls-remote` 读回来比对 SHA。`git` 退出码 0 和"分支真的前进了"不是同一句话——
远端 hook 拒收、代理吃掉请求、推到另一个仓库，都能让退出码好看。

- 非快进被拒 → `USER`，并提示"要么 rebase，要么真的想覆盖才 force"
- 认证失败 → `USER`，提示 token 需要该仓库写权限
- 推成功但 `ls-remote` 对不上 → `THIRD_PART`，直接报两边的 SHA

```bash
mvn verify          # -> dist/git-push/, 加上 src/test/java/atom/GitPushIT.java
```

测试用**真的裸仓库**当远端：验成功推送、非快进被拒、force 覆盖、换目标分支、SHA 回读。
