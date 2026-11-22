# Merged into `git`

这个原子已并入 [`git`](https://github.com/hummerstudio/jenkins-atom-git)：功能没有消失，只是不再是一个独立仓库。

原调用：

```groovy
runAtom(atom: 'git-push', version: '1.latest', inputs: [...])
```

现在的等价写法：

```groovy
runAtom(atom: 'git', version: '1.latest', inputs: [action: 'push', ...])
```

输入名完全一致（git-push 的输入本来就是 git 原子输入的子集）。本仓库只读保留，便于历史构建追溯。
