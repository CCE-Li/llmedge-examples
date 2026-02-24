#!/bin/bash

# 验证CMakeLists.txt修改的脚本
# 用于验证PR审核中要求的4项修改

echo "========== CMakeLists.txt 修改验证 =========="
echo ""

# 1. 验证硬编码路径已删除
echo "1. 检查硬编码路径..."
if grep -q "C:\\\\|/home/" CMakeLists.txt; then
    echo "❌ 发现硬编码路径"
    grep -n "C:\\\\|/home/" CMakeLists.txt
else
    echo "✅ 未发现硬编码路径"
fi

# 2. 验证bark.cpp文件存在且被引用
echo ""
echo "2. 检查bark.cpp文件..."
if [ -f "src/bark.cpp" ]; then
    echo "✅ bark.cpp文件存在"
    if grep -q "bark.cpp" CMakeLists.txt; then
        echo "✅ bark.cpp在CMakeLists.txt中被引用"
    else
        echo "❌ bark.cpp未在CMakeLists.txt中引用"
    fi
else
    echo "❌ bark.cpp文件不存在"
fi

# 3. 验证Vulkan可配置项
echo ""
echo "3. 检查Vulkan可配置项..."
if grep -q "option(ENABLE_VULKAN" CMakeLists.txt; then
    echo "✅ 发现Vulkan可配置选项"
    echo "   - ENABLE_VULKAN: $(grep "option(ENABLE_VULKAN" CMakeLists.txt)"
    echo "   - FORCE_DISABLE_VULKAN_X86: $(grep "option(FORCE_DISABLE_VULKAN_X86" CMakeLists.txt)"
    
    if grep -q "IS_ARM_ARCH" CMakeLists.txt; then
        echo "✅ 发现ARM架构检测"
    else
        echo "❌ 未发现ARM架构检测"
    fi
    
    if grep -q "VULKAN_ENABLED" CMakeLists.txt; then
        echo "✅ 发现Vulkan启用状态变量"
    else
        echo "❌ 未发现Vulkan启用状态变量"
    fi
else
    echo "❌ 未发现Vulkan可配置选项"
fi

# 4. 验证注释废代码已清理
echo ""
echo "4. 检查注释废代码..."
commented_lines=$(grep -c "^#.*if\|^#.*endif\|^#.*else" CMakeLists.txt)
if [ $commented_lines -eq 0 ]; then
    echo "✅ 未发现注释的条件判断代码"
else
    echo "❌ 发现 $commented_lines 行注释的条件判断代码"
    grep -n "^#.*if\|^#.*endif\|^#.*else" CMakeLists.txt
fi

# 检查是否使用了正确的CMake条件判断
if grep -q "if(WIN32)" CMakeLists.txt && grep -q "if(IS_ARM_ARCH)" CMakeLists.txt; then
    echo "✅ 使用了正确的CMake条件判断"
else
    echo "❌ 未使用正确的CMake条件判断"
fi

echo ""
echo "========== 跨平台兼容性检查 =========="

# 检查Windows支持
if grep -q "WIN32" CMakeLists.txt; then
    echo "✅ 支持Windows平台"
else
    echo "❌ 未发现Windows平台支持"
fi

# 检查Linux支持
if grep -q "UNIX" CMakeLists.txt; then
    echo "✅ 支持Linux/Unix平台"
else
    echo "❌ 未发现Linux/Unix平台支持"
fi

# 检查架构支持
if grep -q "CMAKE_SYSTEM_PROCESSOR" CMakeLists.txt; then
    echo "✅ 支持多架构检测"
else
    echo "❌ 未发现架构检测"
fi

echo ""
echo "========== CMake最佳实践检查 =========="

# 检查版本要求
if grep -q "cmake_minimum_required" CMakeLists.txt; then
    echo "✅ 设置了CMake最低版本要求"
else
    echo "❌ 未设置CMake最低版本要求"
fi

# 检查项目定义
if grep -q "project(" CMakeLists.txt; then
    echo "✅ 定义了项目"
else
    echo "❌ 未定义项目"
fi

# 检查现代CMake用法
if grep -q "target_include_directories\|target_link_libraries\|target_compile_options" CMakeLists.txt; then
    echo "✅ 使用了现代CMake目标导向命令"
else
    echo "❌ 未使用现代CMake目标导向命令"
fi

echo ""
echo "========== 验证完成 =========="
echo ""
echo "如需测试构建，请运行："
echo "  mkdir build && cd build"
echo "  cmake .. -DENABLE_VULKAN=ON"
echo "  make -j\$(nproc)"
echo ""
echo "测试x86架构禁用Vulkan："
echo "  cmake .. -DENABLE_VULKAN=ON -DFORCE_DISABLE_VULKAN_X86=ON"
echo ""
echo "测试ARM架构启用Vulkan："
echo "  cmake .. -DCMAKE_SYSTEM_PROCESSOR=armv7 -DENABLE_VULKAN=ON"
