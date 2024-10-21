import("core.base.option")
import("core.project.depend")
import("core.base.task")

function simv_comp()
  if not option.get("no_fsdb") then
    if not os.getenv("VERDI_HOME") then
      print("error: VERDI_HOME is not set!")
      os.exit(1, true)
    end
  end
  local abs_base = os.curdir()
  local chisel_dep_srcs = os.iorun("find " .. abs_base .. " -name \"*.scala\""):split('\n')
  table.join2(chisel_dep_srcs, {path.join(abs_base, "build.sc")})
  depend.on_changed(function ()
    local build_dir = path.join(abs_base, "build")
    if os.exists(build_dir) then os.rmdir(build_dir) end
    task.run("soc", {vcs = true, sim = true, config = option.get("config")}) 
  end,{
    files = chisel_dep_srcs,
    dependfile = path.join("out", "chisel.simv.dep"),
    dryrun = option.get("rebuild")
  })

  local comp_dir = path.join(abs_base, "sim", "simv", "comp")
  if not os.exists(comp_dir) then os.mkdir(comp_dir) end
  local design_vsrc = path.join(abs_base, "build", "rtl")
  local design_csrc = path.join(abs_base, "build", "generated-src")
  local difftest = path.join(abs_base, "dependencies", "difftest")
  local difftest_vsrc = path.join(difftest, "src", "test", "vsrc")
  local difftest_vsrc_common = path.join(difftest_vsrc, "common")
  local difftest_vsrc_top = path.join(difftest_vsrc, "vcs")
  local difftest_csrc = path.join(difftest, "src", "test", "csrc")
  local difftest_csrc_common = path.join(difftest_csrc, "common")
  local difftest_csrc_difftest = path.join(difftest_csrc, "difftest")
  local difftest_csrc_spikedasm = path.join(difftest_csrc, "plugin", "spikedasm")
  local difftest_csrc_vcs = path.join(difftest_csrc, "vcs")
  local difftest_config = path.join(difftest, "config")

  local vsrc = os.files(path.join(design_vsrc, "*v"))
  table.join2(vsrc, os.files(path.join(difftest_vsrc_common, "*v")))
  table.join2(vsrc, os.files(path.join(difftest_vsrc_top, "*v")))

  local csrc = os.files(path.join(design_csrc, "*.cpp"))
  table.join2(csrc, os.files(path.join(difftest_csrc_common, "*.cpp")))
  table.join2(csrc, os.files(path.join(difftest_csrc_difftest, "*.cpp")))
  table.join2(csrc, os.files(path.join(difftest_csrc_spikedasm, "*.cpp")))
  table.join2(csrc, os.files(path.join(difftest_csrc_vcs, "*.cpp")))

  local headers = os.files(path.join(design_csrc, "*.h"))
  table.join2(headers, os.files(path.join(difftest_csrc_common, "*.h")))
  table.join2(headers, os.files(path.join(difftest_csrc_difftest, "*.h")))
  table.join2(headers, os.files(path.join(difftest_csrc_spikedasm, "*.h")))
  table.join2(headers, os.files(path.join(difftest_csrc_vcs, "*.h")))

  local vsrc_filelist_path = path.join(comp_dir, "vsrc.f")
  local vsrc_filelist_contents = ""
  for _, f in ipairs(vsrc) do
    vsrc_filelist_contents = vsrc_filelist_contents .. f .. "\n"
  end
  io.writefile(vsrc_filelist_path, vsrc_filelist_contents)

  local csrc_filelist_path = path.join(comp_dir, "csrc.f")
  local csrc_filelist_contents = ""
  for _, f in ipairs(csrc) do
    csrc_filelist_contents = csrc_filelist_contents .. f .. "\n"
  end
  io.writefile(csrc_filelist_path, csrc_filelist_contents)

  local cxx_flags = "-std=c++11 -static -Wall -DNUM_CORES=1"
  cxx_flags = cxx_flags .. " -I" .. design_csrc
  cxx_flags = cxx_flags .. " -I" .. difftest_csrc_common
  cxx_flags = cxx_flags .. " -I" .. difftest_csrc_difftest
  cxx_flags = cxx_flags .. " -I" .. difftest_csrc_spikedasm
  cxx_flags = cxx_flags .. " -I" .. difftest_config
  if option.get("ref") == "Spike" then
    cxx_flags = cxx_flags .. " -DREF_PROXY=SpikeProxy -DSELECTEDSpike"
  else
    cxx_flags = cxx_flags .. " -DREF_PROXY=NemuProxy -DSELECTEDNemu"
  end
  if option.get("sparse_mem") then
    cxx_flags = cxx_flags .. " -DCONFIG_USE_SPARSEMM"
  end

  local cxx_ldflags = "-Wl,--no-as-needed -lpthread -lSDL2 -ldl -lz -lsqlite3"

  local vcs_flags = "-cm_dir " .. path.join(comp_dir, "simv")
  vcs_flags = vcs_flags .. " -full64 +v2k -timescale=1ns/1ns -sverilog"
  vcs_flags = vcs_flags .. " -debug_access +lint=TFIPC-L -l vcs.log -top tb_top"
  vcs_flags = vcs_flags .. " -fgp -lca -kdb +nospecify +notimingcheck -xprop -no_save"
  vcs_flags = vcs_flags .. " +define+DIFFTEST +define+PRINTF_COND=1 +define+VCS"
  vcs_flags = vcs_flags .. " +define+CONSIDER_FSDB=tb_top.sim"
  vcs_flags = vcs_flags .. " +define+SIM_TOP_MODULE_NAME=tb_top.sim -j200"
  if not option.get("no_fsdb") then
    novas = path.join(os.getenv("VERDI_HOME"), "share", "PLI", "VCS", "LINUX64")
    vcs_flags = vcs_flags .. " -P " .. path.join(novas, "novas.tab")
    vcs_flags = vcs_flags .. " " .. path.join(novas, "pli.a")
  end
  vcs_flags = vcs_flags .. " -CFLAGS \"" .. cxx_flags .. "\""
  vcs_flags = vcs_flags .. " -LDFLAGS \"" .. cxx_ldflags .. "\""
  vcs_flags = vcs_flags .. " -f " .. vsrc_filelist_path
  vcs_flags = vcs_flags .. " -f " .. csrc_filelist_path
  vcs_flags = "vcs " .. vcs_flags
  os.cd(comp_dir)
  io.writefile("vcs_cmd.sh", vcs_flags)

  local depend_srcs = vsrc
  table.join2(depend_srcs, csrc)
  table.join2(depend_srcs, headers)
  depend.on_changed(function()
    print(vcs_flags)
    os.execv(os.shell(), { "vcs_cmd.sh" })
  end, {
    files = depend_srcs,
    dependfile = path.join(comp_dir, "simv.ln.dep"),
    dryrun = option.get("rebuild")
  })
  os.rm("vcs_cmd.sh")
end

-- option: no_dump
-- option: no_diff
-- option: image
-- option: imagez
-- option: case_dir
-- option: ref
-- option: ref_dir

function simv_run()
  assert(option.get("image") or option.get("imagez"))
  local abs_dir = os.curdir()
  local image_file = ""
  local abs_case_base_dir = path.join(abs_dir, option.get("case_dir"))
  local abs_ref_base_dir = path.join(abs_dir, option.get("ref_dir"))
  if option.get("imagez") then image_file = path.join(abs_case_base_dir, option.get("imagez") .. ".gz") end
  if option.get("image") then image_file = path.join(abs_case_base_dir, option.get("image") .. ".bin") end
  local image_basename = path.basename(image_file)
  local sim_dir = path.join("sim", "simv", image_basename)
  local ref_so = path.join(abs_ref_base_dir, option.get("ref"))
  if os.exists(sim_dir) then os.rm(path.join(sim_dir, "*")) else os.mkdir(sim_dir) end
  os.ln(path.join(abs_dir, "sim", "simv", "comp", "simv"), path.join(sim_dir, "simv"))
  os.ln(path.join(abs_dir, "sim", "simv", "comp", "simv.daidir"), path.join(sim_dir, "simv.daidir"))
  os.cd(sim_dir)
  local sh_str = "chmod +x simv" .. " && ( ./simv"
  if not option.get("no_dump") then
    sh_str = sh_str .. " +dump-wave=fsdb"
  end
  if not option.get("no_diff") then
    sh_str = sh_str .. " +diff=" .. ref_so
  else
    sh_str = sh_str .. " +no-diff"
  end
  sh_str = sh_str .. " +workload=" .. image_file
  sh_str = sh_str .. " -fgp=num_threads:4,num_fsdb_threads:4"
  sh_str = sh_str .. " -assert finish_maxfail=30"
  sh_str = sh_str .. " -assert global_finish_maxfail=10000"
  sh_str = sh_str .. " ) 2>assert.log |tee run.log"
  io.writefile("tmp.sh", sh_str)
  print(sh_str)
  os.execv(os.shell(), {"tmp.sh"})
  os.rm("tmp.sh")
end
