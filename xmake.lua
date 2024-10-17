local prj_dir = os.curdir()

task("soc")
   set_menu {
    usage = "xmake soc [options]",
    description = "generate soc rtl",
    options = {
      {nil, "no-split", "k", nil, "do not split generated rtl"},
      {'b', "no-cores", "k", nil, "leave core interfaces to the top"},
      {nil, "no-difftest", "k", nil, "disable difftest"},
      {nil, "no-fpga", "k", nil, "generate simulation verilog"},
      {'r', "release", "k", nil, "export release pack"},
      {'v', "vcs", "k", nil, "export release pack"},
      {nil, "out-dir", "kv", "build/rtl", "assign build dir"},
      {'j', "jobs", "kv", "16", "post-compile process jobs"},
      {'c', "soc-config", "kv", "full", "soc config selection"}
    }
  }
  local chisel_opts =  {"mill", "-i"}
  table.join2(chisel_opts, {"linknan.runMain"})
  table.join2(chisel_opts, {"linknan.generator.SocGenerator"})
  table.join2(chisel_opts, {"--target", "systemverilog", "--full-stacktrace"})

  on_run(function()
    import("core.base.option")
    if not option.get("no-split") then table.join2(chisel_opts, {"--split-verilog"}) end
    if option.get("no-cores") then table.join2(chisel_opts, {"--no-cores"}) end
    if not option.get("no-difftest") then table.join2(chisel_opts, {"--enable-difftest"}) end
    if not option.get("no-fpga") then table.join2(chisel_opts, {"--fpga-platform"}) end
    table.join2(chisel_opts, {"-td", option.get("out-dir")})
    table.join2(chisel_opts, {"--config", option.get("soc-config")})
    os.execv(os.shell(), chisel_opts)

    os.rm(option.get("out-dir") .. "/firrtl_black_box_resource_files.f")
    os.rm(option.get("out-dir") .. "/filelist.f")
    os.rm(option.get("out-dir") .. "/extern_modules.sv")
    local postcompile_opts = {"scripts/linknan/postcompile.py", option.get("out-dir"), "-j", option.get("jobs")}
    if option.get("vcs") then table.join2(postcompile_opts, {"--vcs"}) end
    os.execv("python3", postcompile_opts)

    local harden_table = {"SoC", "CoreWrapper", "CpuCluster", "DCU"}
    if option.get("release") then os.execv("python3", table.join2({"scripts/linknan/release.py"}, harden_table)) end
  end)
task_end()

task("idea")
  on_run(function()
    os.execv(os.shell(), {"mill", "-i", "mill.idea.GenIdea/idea"})
  end)
  set_menu {}
task_end()

task("init")
  on_run(function()
    os.exec("git submodule update --init")
  end)
  set_menu {}
task_end()

task("comp")
  on_run(function()
    os.execv(os.shell(), {"mill", "-i", "linknan.compile"})
    os.execv(os.shell(), {"mill", "-i", "linknan.test.compile"})
  end)
  set_menu {}
task_end()

task("clean")
  on_run(function()
    os.rmdir("build/*")
  end)
  set_menu {}
task_end()