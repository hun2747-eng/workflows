import SwiftUI
import UIKit
import AVFoundation
import Vision

// MARK: - Élő QR-kód olvasó (AVFoundation)

struct QRScannerSheet: View {
    var onFound: (String) -> Void
    var onCancel: () -> Void

    var body: some View {
        NavigationView {
            ZStack {
                QRScannerView(onFound: onFound)
                    .ignoresSafeArea()

                VStack {
                    Spacer()
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.white.opacity(0.9), lineWidth: 3)
                        .frame(width: 250, height: 250)
                    Spacer()
                    Text("Irányítsd a kamerát a QR-kódra")
                        .font(.footnote.bold())
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.6))
                        .cornerRadius(8)
                        .padding(.bottom, 40)
                }
            }
            .navigationTitle("QR-kód olvasó")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Mégse", action: onCancel)
                }
            }
        }
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    var onFound: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let vc = QRScannerController()
        vc.onFound = onFound
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {}
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didFind = false
    private let messageLabel = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        messageLabel.textColor = .white
        messageLabel.numberOfLines = 0
        messageLabel.textAlignment = .center
        messageLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(messageLabel)
        NSLayoutConstraint.activate([
            messageLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            messageLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            messageLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            messageLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24)
        ])

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.showMessage("Nincs kamera-hozzáférés.\nEngedélyezd: Beállítások → NetLokator → Kamera")
                    }
                }
            }
        default:
            showMessage("Nincs kamera-hozzáférés.\nEngedélyezd: Beállítások → NetLokator → Kamera")
        }
    }

    private func showMessage(_ text: String) {
        messageLabel.text = text
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            showMessage("A kamera nem érhető el ezen az eszközön.")
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            showMessage("A QR-olvasó nem indítható.")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        let captureSession = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            captureSession.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopSession()
    }

    private func stopSession() {
        let captureSession = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            if captureSession.isRunning { captureSession.stopRunning() }
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !didFind,
              let code = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = code.stringValue,
              !value.isEmpty else { return }
        didFind = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        stopSession()
        onFound?(value)
    }
}

// MARK: - Fénykép készítése (kamera) / kép választása

struct ImagePicker: UIViewControllerRepresentable {
    var sourceType: UIImagePickerController.SourceType
    var onImage: (UIImage) -> Void
    var onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onImage: onImage, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        if UIImagePickerController.isSourceTypeAvailable(sourceType) {
            picker.sourceType = sourceType
        } else {
            picker.sourceType = .photoLibrary
        }
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onImage: (UIImage) -> Void
        let onCancel: () -> Void

        init(onImage: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onImage = onImage
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                onImage(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}

// MARK: - Link kinyerése képből (QR-kód + szövegfelismerés, eszközön, a Google Lens-hez hasonlóan)

enum LinkExtractor {

    /// Megkeresi a képen lévő linket: először QR-kódot keres, utána a képen látható szövegből olvas ki URL-t.
    static func extractLink(from image: UIImage, completion: @escaping (String?) -> Void) {
        guard let cgImage = image.cgImage else {
            completion(nil)
            return
        }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)

        DispatchQueue.global(qos: .userInitiated).async {
            let result = LinkExtractor.findLink(cgImage: cgImage, orientation: orientation)
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    private static func findLink(cgImage: CGImage, orientation: CGImagePropertyOrientation) -> String? {
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:])

        let barcodeRequest = VNDetectBarcodesRequest()
        barcodeRequest.symbologies = [.qr, .aztec, .dataMatrix, .pdf417]

        let textRequest = VNRecognizeTextRequest()
        textRequest.recognitionLevel = .accurate
        textRequest.usesLanguageCorrection = false

        try? handler.perform([barcodeRequest, textRequest])

        // 1) QR / vonalkód tartalma
        let payloads: [String] = (barcodeRequest.results ?? []).compactMap { $0.payloadStringValue }
        for payload in payloads {
            if let url = firstURL(in: payload) {
                return url
            }
        }

        // 2) Felismert szöveg
        let lines: [String] = (textRequest.results ?? []).compactMap { $0.topCandidates(1).first?.string }

        var candidates: [String] = []
        for line in lines {
            candidates.append(contentsOf: allURLs(in: line))
            // az OCR gyakran szóközt tesz az URL-be
            candidates.append(contentsOf: allURLs(in: line.replacingOccurrences(of: " ", with: "")))
        }
        // több sorba tört hosszú linkek: egymás utáni sorok összefűzése
        if lines.count > 1 {
            for i in 0..<(lines.count - 1) {
                let joined = (lines[i] + lines[i + 1]).replacingOccurrences(of: " ", with: "")
                candidates.append(contentsOf: allURLs(in: joined))
            }
        }

        // a leghosszabb http(s) találat nyer
        return candidates
            .filter { $0.hasPrefix("http://") || $0.hasPrefix("https://") }
            .max(by: { $0.count < $1.count })
    }

    static func firstURL(in text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if (trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")),
           !trimmed.contains(" "),
           URL(string: trimmed) != nil {
            return trimmed
        }
        return allURLs(in: trimmed).first
    }

    static func allURLs(in text: String) -> [String] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return []
        }
        let nsText = text as NSString
        let matches = detector.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        var result: [String] = []
        for match in matches {
            guard let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else { continue }
            var s = url.absoluteString
            // ha a képen séma nélkül szerepelt (pl. "netlokator.hu/..."), https-t használunk
            let original = nsText.substring(with: match.range).lowercased()
            if scheme == "http", !original.hasPrefix("http://"), s.hasPrefix("http://") {
                s = "https://" + String(s.dropFirst(7))
            }
            result.append(s)
        }
        return result
    }
}

extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
