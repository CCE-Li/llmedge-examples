cmake_minimum_required(VERSION 3.22)
project(llmedge-examples VERSION 1.0.0 LANGUAGES CXX)

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 修改1: 移除硬编码路径，使用CMake标准变量
# 原来的硬编码路径已被删除，现在使用CMAKE_SOURCE_DIR等标准变量
# 确保不依赖local.properties文件

# 平台检测
if(WIN32)
    set(PLATFORM_NAME "Windows")
    set(PLATFORM_EXT ".dll")
elseif(APPLE)
    set(PLATFORM_NAME "macOS")
    set(PLATFORM_EXT ".dylib")
elseif(UNIX)
    set(PLATFORM_NAME "Linux")
    set(PLATFORM_EXT ".so")
endif()

# 架构检测
if(CMAKE_SYSTEM_PROCESSOR MATCHES "arm|ARM")
    set(ARCH_TYPE "ARM")
    set(IS_ARM_ARCH TRUE)
else()
    set(ARCH_TYPE "x86")
    set(IS_ARM_ARCH FALSE)
endif()

# 修改2: 恢复bark.cpp文件引用
# 确保bark.cpp文件被正确包含在构建中
set(BARK_CPP_SOURCES
    src/bark.cpp
    # 修改说明: 恢复被误删的bark.cpp文件引用
    # 原因: bark.cpp是TTS功能的核心实现文件，必须包含在构建中
)

# 查找所有源文件
file(GLOB_RECURSE ALL_SOURCES 
    "src/*.cpp"
    "src/*.c"
    "src/*.h"
    "src/*.hpp"
)

# 修改3: Vulkan可配置项实现
# 仅对x86架构禁用Vulkan，ARM设备保持启用
option(ENABLE_VULKAN "Enable Vulkan support" ON)
option(FORCE_DISABLE_VULKAN_X86 "Force disable Vulkan on x86 architecture" OFF)

# Vulkan配置逻辑
if(IS_ARM_ARCH)
    # ARM架构：默认启用Vulkan，除非用户明确禁用
    if(NOT ENABLE_VULKAN)
        message(WARNING "Vulkan disabled on ARM architecture - performance may be impacted")
        set(VULKAN_ENABLED FALSE)
    else()
        set(VULKAN_ENABLED TRUE)
        message(STATUS "Vulkan enabled on ARM architecture")
    endif()
else()
    # x86架构：可配置禁用Vulkan
    if(FORCE_DISABLE_VULKAN_X86 OR NOT ENABLE_VULKAN)
        set(VULKAN_ENABLED FALSE)
        message(STATUS "Vulkan disabled on x86 architecture")
    else()
        set(VULKAN_ENABLED TRUE)
        message(STATUS "Vulkan enabled on x86 architecture")
    endif()
endif()

# 设置Vulkan相关的编译定义
if(VULKAN_ENABLED)
    add_compile_definitions(GGML_VULKAN=ON SD_VULKAN=ON)
    # 查找Vulkan库
    find_package(Vulkan REQUIRED)
else()
    add_compile_definitions(GGML_VULKAN=OFF SD_VULKAN=OFF)
    message(STATUS "Vulkan support disabled")
endif()

# 修改4: 清理注释废代码，使用正确的CMake条件判断
# 原来的注释代码已被删除，替换为以下清晰的逻辑：

# 平台特定的编译选项
if(WIN32)
    # Windows特定配置
    add_compile_definitions(_WIN32_WINNT=0x0601)  # Windows 7+
    if(MSVC)
        add_compile_options(/W4)
        # 禁用一些MSVC警告
        add_compile_options(/wd4244 /wd4267)
    endif()
elseif(UNIX)
    # Linux/Unix特定配置
    add_compile_options(-Wall -Wextra)
    if(CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_compile_options(-g -O0)
    else()
        add_compile_options(-O3)
    endif()
endif()

# 架构特定的编译选项
if(IS_ARM_ARCH)
    # ARM特定优化
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
        add_compile_options(-mcpu=native)
    else()
        add_compile_options(-mfpu=neon)
    endif()
else()
    # x86特定优化
    add_compile_options(-march=native)
endif()

# 创建主库
add_library(llmedge-examples SHARED
    ${ALL_SOURCES}
    ${BARK_CPP_SOURCES}
)

# 修改说明: 使用条件判断替代硬编码注释
# 原因: 提高代码可维护性和可读性

# 包含目录
target_include_directories(llmedge-examples
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

# 链接库
if(VULKAN_ENABLED)
    target_link_libraries(llmedge-examples
        PRIVATE
            Vulkan::Vulkan
    )
endif()

# 平台特定的链接库
if(WIN32)
    target_link_libraries(llmedge-examples
        PRIVATE
            ws2_32
            bcrypt
    )
elseif(UNIX AND NOT APPLE)
    target_link_libraries(llmedge-examples
        PRIVATE
            pthread
            dl
    )
endif()

# 编译器特定的设置
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    target_compile_options(llmedge-examples PRIVATE -fPIC)
elseif(CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    target_compile_options(llmedge-examples PRIVATE -fPIC)
elseif(CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
    target_compile_options(llmedge-examples PRIVATE /MP)
endif()

# 安装配置
include(GNUInstallDirs)

install(TARGETS llmedge-examples
    EXPORT llmedge-examples-targets
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

install(DIRECTORY include/
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}
)

# 导出配置
install(EXPORT llmedge-examples-targets
    FILE llmedge-examples-targets.cmake
    NAMESPACE llmedge::
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/llmedge-examples
)

# 创建配置文件
include(CMakePackageConfigHelpers)

configure_package_config_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/llmedge-examples-config.cmake.in"
    "${CMAKE_CURRENT_BINARY_DIR}/llmedge-examples-config.cmake"
    INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/llmedge-examples
)

write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/llmedge-examples-config-version.cmake"
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)

install(FILES
    "${CMAKE_CURRENT_BINARY_DIR}/llmedge-examples-config.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/llmedge-examples-config-version.cmake"
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/llmedge-examples
)

# 测试配置
enable_testing()

if(BUILD_TESTING)
    add_subdirectory(tests)
endif()

# 配置摘要
message(STATUS "")
message(STATUS "========== LLMEdge Examples Configuration ==========")
message(STATUS "Platform: ${PLATFORM_NAME}")
message(STATUS "Architecture: ${ARCH_TYPE}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Vulkan Enabled: ${VULKAN_ENABLED}")
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID}")
message(STATUS "Install Prefix: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "=====================================================")
message(STATUS "")
