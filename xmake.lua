local prj_dir = os.curdir()

task("soc")
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
task_end()

includes("scripts/xmake/rules/verilator/rules.lua")
includes("scripts/xmake/options/options.lua")

target("emu", function()
  set_toolchains("@verilator")
  add_rules("linknan.emu")
  add_values("verilator.top", "SimTop")
  add_options("threads")
  add_options("dramsim-home")
  add_options("dramsim")
  add_options("ref-model")
  add_options("volatile-chisel")

  on_load(function (target)
    import("core.base.task")
    local rtl_gen_opts = {sim = true, all_in_one = true, config = "minimal"}
    if has_config("dramsim") then
      target:add("values", "verilator.with-dramsim", "true")
      target:add("values", "verilator.dramsim-home", "$(dramsim-home)")
      table.join2(rtl_gen_opts, {dramsim3 = true})
    end
    target:add("values", "verilator.ref-model", "$(ref-model)")
    target:add("values", "verilator.threads", "$(threads)")
    if has_config("volatile-chisel") then task.run("soc", rtl_gen_opts) end
  end)
end)

task("emu_run", function ()
  set_menu {
    usage = "xmake emu_run [options]",
    description = "Run emu",
    options = {
      {'d', "dump", "k", nil, "dump full wave and disable fork"},
      {'i', "image", "kv", nil, "image bin name"},
      {'z', "imagez", "kv", nil, "image gz name"},
      {'b', "base", "kv", "ready-to-run", "image base dir"},
      {'r', "ref", "kv", "riscv64-spike-so", "reference model"},
      {'s', "seed", "kv", "1234", "reference model"},
    }
  }
  local chisel_opts =  {"mill", "-i"}

  on_run(function()
    import("core.base.option")
    assert(option.get("image") or option.get("imagez"))
    local abs_dir = os.curdir()
    local image_file = ""
    local abs_base_dir = path.join(abs_dir, option.get("base"))
    if option.get("imagez") then image_file = path.join(abs_base_dir, option.get("imagez") .. ".gz") end
    if option.get("image") then image_file = path.join(abs_base_dir, option.get("image") .. ".bin") end
    local image_basename = path.basename(image_file)
    local sim_dir = path.join("sim", "emu", image_basename)
    local ref_so = path.join(abs_base_dir, option.get("ref"))
    if os.exists(sim_dir) then os.rm(path.join(sim_dir, "*")) else os.mkdir(sim_dir) end
    os.ln(path.join(abs_dir, "sim", "emu", "comp", "emu"), path.join(sim_dir, "emu"))
    os.cd(sim_dir)
    local sh_str = "chmod +x emu" .. " && ( ./emu"
    if not option.get("dump") then
      sh_str = sh_str .. " --enable-fork --fork-interval=15"
    else
      sh_str = sh_str .. " --dump-wave-full"
    end
    sh_str = sh_str .. " --diff " .. ref_so
    sh_str = sh_str .. " -i " .. image_file
    sh_str = sh_str .. " -s " .. option.get("seed")
    sh_str = sh_str .. " --wave-path dump.vcd"
    sh_str = sh_str .. " ) 2>assert.log |tee run.log"
    io.writefile("tmp.sh", sh_str)
    os.execv(os.shell(), {"tmp.sh"})
    print(sh_str)
    os.rm("tmp.sh")
  end)
end)

task("simv", function()
  set_menu {
    usage = "xmake simv [options]",
    description = "Compile with vcs",
    options = {
      {'g', "generate_rtl", "k", nil, "do not dump wave"},
      {'d', "no_fsdb", "k", nil, "do not dump wave"},
      {'s', "sparse_mem", "k", nil, "use sparse mem"},
      {'r', "ref", "kv", "Spike", "image bin name"},
      {'c', "config", "kv", "minimal", "image bin name"}
    }
  }

  on_run(function()
    import("core.base.task")
    import("core.base.option")
    if option.get("generate_rtl") then 
      local build_dir = path.join(os.curdir(), "build")
      if os.exists(build_dir) then os.rmdir(build_dir) end
      task.run("soc", {vcs = true, sim = true, config = option.get("config")}) 
    end
    import("scripts.xmake.rules.vcs.vcs").simv_comp()
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
    os.rmdir("build/*")
    os.rmdir("sim/*")
  end)
  set_menu {}
end)