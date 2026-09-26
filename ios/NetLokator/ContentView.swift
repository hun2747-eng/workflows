import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var historyStore = HistoryStore.shared
    @State private var baseUrlInput = NetworkService.shared.baseUrl.isEmpty ? "https://netlokator.hu" : NetworkService.shared.baseUrl
    @State private var apiKeyInput = NetworkService.shared.apiKey
    @State private var urlInput = ""
    @State private var isSending = false
    @State private var statusMessage = ""
    @State private var statusIsError = false
    @State private var isLoggedIn = NetworkService.shared.isLoggedIn
    @State private var showSettings = false
    @State private var settingsSuccessMsg = ""
    @State private var settingsErrorMsg = ""

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    headerView

                    if !isLoggedIn || showSettings {
                        settingsCard
                    }

                    if isLoggedIn {
                        submissionCard
                    }

                    historySection
                }
                .padding()
            }
            .navigationBarTitleDisplayMode(.inline)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .onAppear {
                historyStore.loadHistory()
                if baseUrlInput.isEmpty {
                    baseUrlInput = "https://netlokator.hu"
                }
                isLoggedIn = NetworkService.shared.isLoggedIn
                autoSendFromClipboardIfNeeded()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                autoSendFromClipboardIfNeeded()
            }
        }
    }

    private var headerView: some View {
        VStack(spacing: 6) {
            Image(systemName: "network")
                .font(.system(size: 44))
                .foregroundColor(.blue)
            Text("NetLokátor")
                .font(.title2.bold())
            Text("URL indexelés & megosztás")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .padding(.top, 10)
    }

    private var settingsCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(isLoggedIn ? "Beállítások módosítása" : "Bejelentkezés / Beállítások")
                    .font(.headline)
                Spacer()
                if isLoggedIn {
                    Button("Bezárás") {
                        showSettings = false
                    }
                    .font(.footnote)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Base URL:").font(.caption).foregroundColor(.secondary)
                TextField("https://netlokator.hu", text: $baseUrlInput)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("X-API-Key (kötelező):").font(.caption).foregroundColor(.secondary)
                TextField("Add meg a NetLokátor API-kulcsot", text: $apiKeyInput)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }

            if !settingsErrorMsg.isEmpty {
                Text(settingsErrorMsg)
                    .font(.footnote)
                    .foregroundColor(.red)
            }
            if !settingsSuccessMsg.isEmpty {
                Text(settingsSuccessMsg)
                    .font(.footnote)
                    .foregroundColor(.green)
            }

            Button(action: saveSettings) {
                HStack {
                    Spacer()
                    Text("Mentés & Bejelentkezés")
                        .bold()
                    Spacer()
                }
                .padding(.vertical, 10)
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(8)
            }

            if isLoggedIn {
                Button(action: logoutAction) {
                    HStack {
                        Spacer()
                        Text("Kijelentkezés")
                            .font(.subheadline)
                            .foregroundColor(.red)
                        Spacer()
                    }
                    .padding(.top, 4)
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }

    private var submissionCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Közvetlen link küldése").font(.headline)
                Spacer()
                Button(action: { showSettings.toggle() }) {
                    Text(showSettings ? "Bezárás" : "Beállítások")
                        .font(.footnote)
                }
            }

            HStack {
                TextField("https://...", text: $urlInput)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                Button(action: pasteFromClipboard) {
                    Image(systemName: "doc.on.clipboard")
                        .padding(8)
                        .background(Color(.tertiarySystemFill))
                        .cornerRadius(6)
                }
            }

            Button(action: sendUrlAction) {
                HStack {
                    Spacer()
                    if isSending {
                        ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                    } else {
                        Text("START – URL KÜLDÉSE").bold()
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
                .background(urlInput.isEmpty ? Color.gray : Color.green)
                .foregroundColor(.white)
                .cornerRadius(8)
            }
            .disabled(urlInput.isEmpty || isSending)

            if !statusMessage.isEmpty {
                Text(statusMessage)
                    .font(.footnote)
                    .foregroundColor(statusIsError ? .red : .green)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Beküldött linkek előzményei")
                    .font(.headline)
                Spacer()
                if !historyStore.links.isEmpty {
                    Button(action: { historyStore.clearHistory() }) {
                        Text("Törlés").font(.footnote).foregroundColor(.red)
                    }
                }
            }

            if historyStore.links.isEmpty {
                VStack(spacing: 12) {
                    Text("Még nincs beküldött link az előzményekben.")
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    Button(action: addTestLink) {
                        Text("+ Teszt link hozzáadása")
                            .font(.footnote)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.blue.opacity(0.15))
                            .foregroundColor(.blue)
                            .cornerRadius(8)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(.secondarySystemGroupedBackground))
                .cornerRadius(10)
            } else {
                ForEach(historyStore.links) { item in
                    historyRow(item)
                }
            }
        }
    }

    private func historyRow(_ item: SubmittedLink) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(item.timestamp, style: .date)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Text(item.timestamp, style: .time)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Spacer()
                Text(item.status)
                    .font(.caption2.bold())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(item.status == "Sikeres" ? Color.green.opacity(0.2) : Color.red.opacity(0.2))
                    .foregroundColor(item.status == "Sikeres" ? .green : .red)
                    .cornerRadius(4)
            }

            Text(item.url)
                .font(.footnote)
                .foregroundColor(.blue)
                .lineLimit(2)
                .onTapGesture {
                    if let url = URL(string: item.url) {
                        UIApplication.shared.open(url)
                    }
                }
                .contextMenu {
                    Button("Másolás vágólapra") {
                        UIPasteboard.general.string = item.url
                    }
                }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(8)
    }

    private func saveSettings() {
        let cleanBase = baseUrlInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalBase = cleanBase.isEmpty ? "https://netlokator.hu" : cleanBase
        let cleanKey = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)

        if cleanKey.isEmpty {
            settingsErrorMsg = "Kérlek add meg a NetLokátor API-kulcsot!"
            settingsSuccessMsg = ""
            return
        }

        NetworkService.shared.baseUrl = finalBase
        NetworkService.shared.apiKey = cleanKey
        baseUrlInput = finalBase
        apiKeyInput = cleanKey

        settingsErrorMsg = ""
        settingsSuccessMsg = "Sikeres mentés és bejelentkezés!"
        isLoggedIn = true
        showSettings = false
    }

    private func logoutAction() {
        NetworkService.shared.apiKey = ""
        apiKeyInput = ""
        isLoggedIn = false
        settingsSuccessMsg = ""
        settingsErrorMsg = ""
    }

    private func pasteFromClipboard() {
        if let clip = UIPasteboard.general.string {
            urlInput = clip.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private func autoSendFromClipboardIfNeeded() {
        guard NetworkService.shared.isLoggedIn else { return }
        let cc = UIPasteboard.general.changeCount
        let lastCC = UserDefaults.standard.integer(forKey: "lastPasteChangeCount")
        guard cc != lastCC else { return }
        UserDefaults.standard.set(cc, forKey: "lastPasteChangeCount")

        guard UIPasteboard.general.hasStrings || UIPasteboard.general.hasURLs else { return }
        let clip = (UIPasteboard.general.url?.absoluteString ?? UIPasteboard.general.string ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard clip.hasPrefix("http://") || clip.hasPrefix("https://") else { return }
        guard !isSending else { return }

        urlInput = clip
        sendUrlAction()
    }

    private func sendUrlAction() {
        guard !urlInput.isEmpty else { return }
        isSending = true
        statusMessage = ""

        Task {
            do {
                let res = try await NetworkService.shared.sendUrl(rawUrl: urlInput)
                await MainActor.run {
                    isSending = false
                    statusIsError = !res.success
                    statusMessage = res.message
                    urlInput = ""
                }
            } catch {
                await MainActor.run {
                    isSending = false
                    statusIsError = true
                    statusMessage = error.localizedDescription
                }
            }
        }
    }

    private func addTestLink() {
        historyStore.addLink(url: "https://example.com/netlokator-test-ios", status: "Sikeres", message: "Teszt bejegyzés")
    }
}
