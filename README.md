> ## ⚠ これは当部パッチ版です（eruto-mc）
>
> 上流: [alRex-U/ParCool](https://github.com/alRex-U/ParCool) ／ ブランチ `eruto/world3-1.20.1`
> ／ ライセンスは上流に従う（LGPL-3.0）。**以下は上流の README です。**
>
> **当部が変えたところ**（コミット5件）:
>
> | 何を | なぜ |
> | - | - |
> | Vault の視線許容を 45° → **60°** に広げた | 狙って飛び越えたいのに反応しないことが多かった |
> | Vault のロックを 11 → **8** tick、CatLeap のクールダウンを 30 → **20** tick に短縮 | 連続した動きが途切れる感じを減らす |
> | Breakfall に **Auto** の操作種別を足した | 受け身をキー入力なしで出せるようにする |
> | 壁に触れてバニラがダッシュを切ったときも **FastRun を継続**するようにした | 壁ぎわで速度が落ちるのを直す |
> | jar 名とゲーム内の版に**当部のパッチ番号**を出すようにした | 配ったのがどの版か分かるようにする |
| ⚠ **属性 `parcool:wall_climb` を足した**（既定 1.0）。垂直ウォールランの**始めの一押し**（`0.32 × √壁の高さ`）に掛け、`canStart` の**間隔 15 ティック**を割る | ⚠ **登れる高さを種族ごとに変えたい**（アラクネ＝壁を登る種族／ネコ獣人＝その中間）。⚠⚠ 上流は**数が直書き**で、`Limitation` は**制限しかできない**（`Math.max` で合成する＝下げられない）ので、fork でしか触れなかった。⚠ 属性にしたので Origins 側は `origins:attribute` 1行で済み、コマンドも再ログインの心配も要らない |
>
> ビルドは `gradle.properties` の `eruto_patch` が版を決める。

![ParCool_Logo](./parcool_logo.png)

# ParCool MOD

**Welcome to this project!**

*ParCool* is a mod of Minecraft, for more *Cool* Actions like *Parkour*.  
It's inspired by [SmartMoving](https://www.curseforge.com/minecraft/mc-mods/smart-moving). That was a very great mod.

Players can do more actions such as...

+ Grabbing Cliffs
+ Running Faster
+ Roll
+ Backflip
+ WallJump
+ CatLeap  
  etc

If it made you traceurs or traceuses ; parkour practitioners, I couldn't be happier!!

This project is always ready to accept your contribution.

### For Developers

This mod provides some features for mod developers, server-hosts and mod-packers.
Please read [ParCool Guide](docs/parcool-guide-on-web-v3.1.0.0/Introduction.md).

*ParCool* is licensed with  
**GNU LESSER GENERAL PUBLIC LICENSE Version 3**.
