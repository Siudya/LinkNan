local prj_dir = os.curdir()

task("soc" , function()
  set_menu {
    usage = "xmake soc [options]",
    description = "Generate soc rtl",
    options = {
      {'a', "all_in_one", "k", nil, "do not split generated rtl"},
      {'b', "block_test_cache", "k", nil, "leave core interfaces to the top"},
      {'c', "clean_difftest", "k", nil, "generate verilog without any difftest components"},
      {'d', "dramsim3", "k", nil, "use dramsim3 as simulation main memory"},
      {'e', "enable_perf", "k", nil, "generate verilog with perf debug components"},
      {'g', "vcs", "k", nil, "alter assertions info to be vcs style"},
      {'r', "release", "k", nil, "export release pack"},
      {'s', "sim", "k", nil, "generate simulation top"},
      {'p', "pldm_verilog", "k", nil, "enable only basic difftest function"},
      {'f', "config", "kv", nil, "soc config selection"},
      {'o', "out_dir", "kv", "build/rtl", "assign build dir"},
      {'j', "jobs", "kv", "16", "post-compile process jobs"}
    }
  }
  local chisel_opts =  {"mill", "-i"}

  on_run(function()
    import("core.base.option")
    print(option.get("options"))
    if option.get("sim") then table.join2(chisel_opts, {"linknan.test.runMain"}) else table.join2(chisel_opts, {"linknan.runMain"}) end
    if option.get("sim") then table.join2(chisel_opts, {"lntest.top.SimGenerator"}) else table.join2(chisel_opts, {"linknan.generator.SocGenerator"}) end
    if not option.get("all_in_one") or option.get("release") then table.join2(chisel_opts, {"--split-verilog"}) end
    if option.get("block_test_cache") then table.join2(chisel_opts, {"--no-cores"}) end
    if not option.get("clean_difftest") and option.get("pldm_verilog") then table.join2(chisel_opts, {"--basic-difftest"}) end
    if not option.get("clean_difftest") and not option.get("pldm_verilog") then table.join2(chisel_opts, {"--enable-difftest"}) end
    if not option.get("enable_perf") then table.join2(chisel_opts, {"--fpga-platform"}) end
    if option.get("sim") and option.get("dramsim3") then table.join2(chisel_opts, {"--dramsim3"}) end
    if option.get("config") then table.join2(chisel_opts, {"--config", option.get("config")}) end
    local build_dir = path.join("build", "rtl")
    if not option.get("sim") and not option.get("release") then build_dir = option.get("out_dir") end
    if option.get("sim") then os.setenv("NOOP_HOME", os.curdir()) end 
    table.join2(chisel_opts, {"--target", "systemverilog", "--full-stacktrace"})
    table.join2(chisel_opts, {"-td", build_dir})
    os.execv(os.shell(), chisel_opts)

    os.rm(path.join(build_dir, "firrtl_black_box_resource_files.f"))
    os.rm(path.join(build_dir, "filelist.f"))
    os.rm(path.join(build_dir, "extern_modules.sv"))

    local py_exec = "python3"
    if os.host() == "windows" then py_exec = "python" end
    local pcmp_scr_path = path.join("scripts", "linknan", "postcompile.py")
    local postcompile_opts = {pcmp_scr_path, build_dir, "-j", option.get("jobs")}
    if option.get("vcs") then table.join2(postcompile_opts, {"--vcs"}) end
    os.execv(py_exec, postcompile_opts)

    local harden_table = {"LNTop", "CoreWrapper", "CpuCluster", "DCU"}
    local rel_scr_path = path.join("scripts", "linknan", "release.py")
    if option.get("release") then os.execv(py_exec, table.join2(rel_scr_path, harden_table)) end
  end)
end)

task("emu", function()
  set_menu {
    usage = "xmake emu [options]",
    description = "Compile with verilator",
    options = {
      {'r', "rebuild", "k", nil, "forcely rebuild"},
      {'s', "sparse_mem", "k", nil, "use sparse mem"},
      {nil, "dramsim3", "k", nil, "use dramsim3"},
      {nil, "dramsim3_home", "kv", "", "dramsim3 home dir"},
      {'t', "threads", "kv", "16", "simulation threads"},
      {'j', "jobs", "kv", "16", "compilation jobs"},
      {'r', "ref", "kv", "Spike", "reference model"},
      {'c', "config", "kv", "minimal", "soc config"}
    }
  }

  on_run(function()
    import("scripts.xmake.verilator").emu_comp()
  end)
end)

task("emu-run", function ()
  set_menu {
    usage = "xmake emu_run [options]",
    description = "Run emu",
    options = {
      {'d', "dump", "k", nil, "dump full wave and disable fork"},
      {'i', "image", "kv", nil, "bin image bin name"},
      {'z', "imagez", "kv", nil, "gz image name"},
      {nil, "case_dir", "kv", "ready-to-run", "image base dir"},
      {nil, "ref", "kv", "riscv64-spike-so", "reference model"},
      {nil, "ref_dir", "kv", "ready-to-run", "reference model base dir"},
      {'s', "seed", "kv", "1234", "random seed"},
    }
  }

  on_run(function()
    import("scripts.xmake.verilator").emu_run()
  end)
end)

task("simv", function()
  set_menu {
    usage = "xmake simv [options]",
    description = "Compile with vcs",
    options = {
      {'r', "rebuild", "k", nil, "forcely rebuild"},
      {'d', "no_fsdb", "k", nil, "do not dump wave"},
      {'s', "sparse_mem", "k", nil, "use sparse mem"},
      {'r', "ref", "kv", "Spike", "reference model"},
      {'c', "config", "kv", "minimal", "rtl config"}
    }
  }

  on_run(function()
    import("scripts.xmake.vcs").simv_comp()
  end)
end)

task("simv-run", function ()
  set_menu {
    usage = "xmake simv-run [options]",
    description = "Run simv",
    options = {
      {nil, "no_dump", "k", nil, "do not dump waveform"},
      {nil, "no_diff", "k", nil, "disable difftest"},
      {'i', "image", "kv", nil, "bin image bin name"},
      {'z', "imagez", "kv", nil, "gz image name"},
      {nil, "case_dir", "kv", "ready-to-run", "image base dir"},
      {nil, "ref", "kv", "riscv64-spike-so", "reference model"},
      {nil, "ref_dir", "kv", "ready-to-run", "reference model base dir"}
    }
  }

  on_run(function()
    import("scripts.xmake.vcs").simv_run()
  end)
end)

task("verdi", function ()
  set_menu {
    usage = "xmake verdi [options]",
    description = "Display waveform with verdi",
    options = {
      {'e', "verilator", "k", nil, "display emu .vcd waveform"},
      {'i', "image", "kv", nil, "image name"},
    }
  }

  on_run(function () 
    import("scripts.xmake.verdi").verdi()
  end)
end)

task("idea", function()
  on_run(function()
    os.execv(os.shell(), {"mill", "-i", "mill.idea.GenIdea/idea"})
  end)
  set_menu {}
end)

task("init", function()
  on_run(function()
    os.exec("git submodule update --init")
  end)
  set_menu {}
end)

task("comp", function()
  on_run(function()
    os.execv(os.shell(), {"mill", "-i", "linknan.compile"})
    os.execv(os.shell(), {"mill", "-i", "linknan.test.compile"})
  end)
  set_menu {}
end)

task("clean", function()
  on_run(function()
    os.rmdir(path.join("build", "*"))
    os.rmdir(path.join("sim", "*"))
    os.rm(path.join("out", "*.dep"))
  end)
  set_menu {}
end)