local prj_dir = os.curdir()

local export_opts = " --target systemverilog --split-verilog --fpga-platform --enable-difftest --full-stacktrace"

target("cluster")
  set_kind("phony")
  on_run(function (target)
    local export_dir = "build/rtl/cluster"
    local cmd_line = "mill -i nansha.runMain nansha.generator.CpuClusterGenerator -td " .. export_dir
    os.execv(os.shell(), {cmd_line .. export_opts})
    os.rm(export_dir .. "/firrtl_black_box_resource_files.f")
    os.rm(export_dir .. "/filelist.f")
    os.rm(export_dir .. "/extern_modules.sv")
  end)
target_end()

target("soc")
  set_kind("phony")
  on_run(function (target)
    local export_dir = "build/rtl/uncore"
    local cmd_line = "mill -i nansha.runMain nansha.generator.SocGenerator -td " .. export_dir
    os.execv(os.shell(), {cmd_line .. export_opts})
    os.rm(export_dir .. "/firrtl_black_box_resource_files.f")
    os.rm(export_dir .. "/filelist.f")
    os.rm(export_dir .. "/extern_modules.sv")
  end)
target_end()

target("idea")
  set_kind("phony")

  on_run(function (target)
    os.execv(os.shell(), {"mill", "-i", "mill.idea.GenIdea/idea"})
  end)
target_end()

target("init")
  set_kind("phony")

  on_run(function (target)
    os.exec("git submodule update --init")
  end)
target_end()

target("comp")
  set_kind("phony")

  on_run(function (target)
    os.execv(os.shell(), {"mill", "-i", "nansha.compile"})
    os.execv(os.shell(), {"mill", "-i", "nansha.test.compile"})
  end)
target_end()

target("clean")
  set_kind("phony")

  on_run(function (target)
    os.rmdir("build/*")
  end)
target_end()