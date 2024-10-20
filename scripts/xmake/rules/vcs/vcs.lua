function simv_comp()
  import("core.base.option")
  if not option.get("no_fsdb") then
    if not os.getenv("VERDI_HOME") then
      print("error: VERDI_HOME is not set!")
      os.exit(1, true)
    end
  end
  local abs_base = os.curdir()
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
  vcs_flags = vcs_flags .. " -fgp -lca -kdb +nospecify +notimingcheck -xprop"
  vcs_flags = vcs_flags .. " +define+DIFFTEST +define+PRINTF_COND=1 +define+VCS"
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
  print(vcs_flags)
  os.execv(os.shell(), { "vcs_cmd.sh" })
  os.rm("vcs_cmd.sh")
end

function simv_run()
end
