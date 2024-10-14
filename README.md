# Nansha
`Nansha` is the top-level of the [ZhuJiang](https://github.com/Siudya/ZhuJiang) RingBus-based NoC system which integrates the [Nanhu](https://github.com/Siudya/Nanhu) high performance RISC-V core and ZhuJiang interconnect.

# Compile source code
Notice: Make sure that you have installed `xmake`. If not, please refer to the [xmake](https://github.com/xmake-io/xmake) official website for installation.
```bash
xmake run init
xmake run comp
xmake run cluster
xmake run soc
```
