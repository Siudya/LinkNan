local prj_dir = os.curdir()

task("soc")
   set_menu {
    usage = "xmake soc [options]",
    description = "generate soc rtl",
    options = {
      {'a', "all-in-one", "k", nil, "do not split generated rtl"},
      {'b', "block-test-cache", "k", nil, "leave core interfaces to the top"},
      {'c', "clean-verilog", "k", nil, "generate verilog without any debug components"},
      {'d', "dramsim3", "k", nil, "use dramsim3 as simulation main memory"},
      {'e', "enable-perf", "k", nil, "generate verilog without perf debug components"},
      {'g', "vcs", "k", nil, "alter assertions info to be vcs style"},
      {'r', "release", "k", nil, "export release pack"},
      {'s', "sim-top", "k", nil, "generate simulation top"},
      {'p', "pldm-verilog", "k", nil, "enable only basic difftest function"},
      {'f', "soc-config", "kv", nil, "soc config selection"},
      {'o', "out-dir", "kv", "build/rtl", "assign build dir"},
      {'j', "jobs", "kv", "16", "post-compile process jobs"}
    }
  }
  local chisel_opts =  {"mill", "-i"}

  on_run(function()
    import("core.base.option")
    if option.get("sim-top") then table.join2(chisel_opts, {"linknan.test.runMain"}) else table.join2(chisel_opts, {"linknan.runMain"}) end
    if option.get("sim-top") then table.join2(chisel_opts, {"lntest.top.SimGenerator"}) else table.join2(chisel_opts, {"linknan.generator.SocGenerator"}) end
    if not option.get("all-in-one") or option.get("release") then table.join2(chisel_opts, {"--split-verilog"}) end
    if option.get("block-test-cache") then table.join2(chisel_opts, {"--no-cores"}) end
    if not option.get("clean-verilog") and option.get("pldm-verilog") then table.join2(chisel_opts, {"--basic-difftest"}) end
    if not option.get("clean-verilog") and not option.get("pldm-verilog") then table.join2(chisel_opts, {"--enable-difftest"}) end
    if not option.get("enable-perf") then table.join2(chisel_opts, {"--fpga-platform"}) end
    if option.get("sim-top") and option.get("dramsim3") then table.join2(chisel_opts, {"--dramsim3"}) end
    if option.get("soc-config") then table.join2(chisel_opts, {"--config", option.get("soc-config")}) end
    table.join2(chisel_opts, {"--target", "systemverilog", "--full-stacktrace"})
    table.join2(chisel_opts, {"-td", option.get("out-dir")})
    os.execv(os.shell(), chisel_opts)

    os.rm(option.get("out-dir") .. "/firrtl_black_box_resource_files.f")
    os.rm(option.get("out-dir") .. "/filelist.f")
    os.rm(option.get("out-dir") .. "/extern_modules.sv")

    local py_exec = "python3"
    if os.host() == "windows" then py_exec = "python" end
    local postcompile_opts = {"scripts/linknan/postcompile.py", option.get("out-dir"), "-j", option.get("jobs")}
    if option.get("vcs") then table.join2(postcompile_opts, {"--vcs"}) end
    os.execv(py_exec, postcompile_opts)

    local harden_table = {"LNTop", "CoreWrapper", "CpuCluster", "DCU"}
    if option.get("release") then os.execv(py_exec, table.join2({"scripts/linknan/release.py"}, harden_table)) end
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