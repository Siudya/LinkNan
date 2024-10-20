rule("linknan.emu", function()
  add_deps("c++")
  set_extensions(".v", ".sv")

  after_load(function(target)
    target:set("optimize", "fastest")
    target:set("toolset", "cc", "clang")
    target:set("toolset", "cxx", "clang++")
    target:set("toolset", "ld", "clang++")
    target:set("values", "verilator.compdir", path.join("sim", "emu", "comp"))

    local difftest = path.join("dependencies", "difftest")
    local difftest_vsrc = path.join(difftest, "src", "test", "vsrc")
    local difftest_vsrc_common = path.join(difftest_vsrc, "common")
    local difftest_csrc = path.join(difftest, "src", "test", "csrc")
    local difftest_csrc_common = path.join(difftest_csrc, "common")
    local difftest_csrc_difftest = path.join(difftest_csrc, "difftest")
    local difftest_csrc_spikedasm = path.join(difftest_csrc, "plugin", "spikedasm")
    local difftest_csrc_verilator = path.join(difftest_csrc, "verilator")
    local difftest_config = path.join(difftest, "config")

    target:add("includedirs", difftest_config)
    target:add("files", path.join(difftest_vsrc_common, "*.sv"))

    target:add("includedirs", difftest_csrc_common)
    target:add("files", path.join(difftest_csrc_common, "*.cpp"))

    target:add("includedirs", difftest_csrc_difftest)
    target:add("files", path.join(difftest_csrc_difftest, "*.cpp"))

    target:add("includedirs", difftest_csrc_spikedasm)
    target:add("files", path.join(difftest_csrc_spikedasm, "*.cpp"))

    target:add("includedirs", difftest_csrc_verilator)
    target:add("files", path.join(difftest_csrc_verilator, "*.cpp"))

    target:add("includedirs", "build/generated-src")
    target:add("files", "build/rtl/*.sv")
    target:add("files", "build/generated-src/*.cpp")

    target:set("kind", "binary")
    target:add("values", "verilator.flags", "--stats-vars", "-Wno-UNOPTTHREADS", "-Wno-STMTDLY", "--trace")
    target:add("values", "verilator.flags", "-Wno-WIDTH", "--assert", "--x-assign", "unique", "--trace")
    target:add("values", "verilator.flags", "--output-split", "30000", "--output-split-cfuncs", "30001")
    target:add("values", "verilator.flags", "+define+PRINTF_COND=1", "+define+DIFFTEST", "+define+STOP_COND_=1")
    target:add("values", "verilator.flags", "+define+ASSERT_VERBOSE_COND_=1", "--no-timing")
    target:add("defines", "VERILATOR", "VM_TRACE=1")
    target:add("links", "dl", "z", "atomic")

    if target:values("verilator.with-dramsim") then
      target:add("defines", "WITHDRAMSIM3")
      local dramsim_home = target:values("verilator.dramsim-home")
      local dramsim_conf = path.join(dramsim_home, "config", "XiangShan.ini")
      assert(os.isfile(dramsim_conf), "dramsim-home must be set correctly!")
      target:add("defines", "DRAMSIM3HOME=\"" .. dramsim_home .. "\"")
      target:add("defines", "DRAMSIM3_OUTDIR=\"" .. target:values("verilator.compdir") .. "\"")
      target:add("ldflags", path.join(dramsim_home, "build", "libdramsim3.a"))
    end

    if target:values("verilator.ref-model") == "Spike" then
      target:add("defines", "REF_PROXY=SpikeProxy", "SELECTEDSpike")
    else
      target:add("defines", "REF_PROXY=NemuProxy", "SELECTEDNemu")
    end

    if target:values("verilator.threads") then
      target:add("values", "verilator.flags", "--threads", target:values("verilator.threads"), "--threads-dpi", "all")
      target:add("defines", "EMU_THREAD=" .. target:values("verilator.threads"))
    end

    if target:values("verilator.print") then
      print("verilator inputs = ")
      print(target:get("values"))
      print("cxx includedirs = ")
      print(target:get("includedirs"))
      print("cxx defines = ")
      print(target:get("defines"))
      print("cxx ldflags = ")
      print(target:get("ldflags"))
      print("cxx links = ")
      print(target:get("links"))
    end
  end)

  on_config(function(target)
    import("verilator").config(target)
  end)

  before_build_files(function(target, sourcebatch)
    -- Just to avoid before_buildcmd_files being executed at build time
  end)

  on_build_files(function(target, batchjobs, sourcebatch, opt)
    import("verilator").build_cppfiles(target, batchjobs, sourcebatch, opt)
  end, { batch = true, distcc = true })

  after_build(function(target)
    os.mv(target:targetfile(), path.join(target:values("verilator.compdir"), target:name()))
  end)
end)
