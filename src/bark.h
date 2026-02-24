/**
 * Bark.h - Text-to-Speech Header
 * 
 * Header file for Bark TTS (Text-to-Speech) functionality
 * in the LLMEdge examples project.
 */

#ifndef BARK_H
#define BARK_H

#include <string>
#include <vector>
#include <memory>
#include <functional>

namespace bark {

// Configuration structure for Bark TTS
struct BarkConfig {
    std::string model_path;
    int sample_rate = 24000;
    int n_threads = 4;
    bool use_gpu = true;
    
    BarkConfig() = default;
    BarkConfig(const std::string& path) : model_path(path) {}
};

// Main Bark TTS class
class BarkTTS {
public:
    BarkTTS();
    ~BarkTTS();
    
    // Initialize the TTS engine with configuration
    void initialize(const BarkConfig& config);
    
    // Generate speech from text
    std::vector<float> generateSpeech(const std::string& text);
    
    // Set progress callback (0-100%)
    void setProgressCallback(std::function<void(int)> callback);
    
    // Disable copy constructor and assignment
    BarkTTS(const BarkTTS&) = delete;
    BarkTTS& operator=(const BarkTTS&) = delete;
    
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

// Factory function
std::unique_ptr<BarkTTS> createBarkTTS();

} // namespace bark

#endif // BARK_H
