package id.afterlife.updater;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.commonmark.node.StrongEmphasis;

import id.afterlife.updater.misc.Utils; // Jangan lupa import Utils
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.core.MarkwonTheme;

public class ChangelogsFragment extends Fragment {
    private TextView mChangelogText;
    private ProgressBar mProgressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_changelogs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mChangelogText = view.findViewById(R.id.changelog_text);
        mProgressBar = view.findViewById(R.id.loading_progress);

        fetchChangelog();
    }

    private void fetchChangelog() {
        mProgressBar.setVisibility(View.VISIBLE);
        mChangelogText.setAlpha(0f);

        String dynamicUrl = Utils.getChangelogRawURL(requireContext());
        Log.d("Changelog", "Fetching from: " + dynamicUrl);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            StringBuilder result = new StringBuilder();
            try {
                URL url = new URL(dynamicUrl); // Gunakan URL dinamis
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                // Timeout settings
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                Log.e("Changelog", "Error fetching data", e);
                result.setLength(0);
            }

            String finalMarkdown = result.toString();

            handler.post(() -> {
                if (isAdded()) {
                    mProgressBar.setVisibility(View.GONE);
                    if (!finalMarkdown.isEmpty()) {
                        renderMarkdown(finalMarkdown);
                        mChangelogText.setVisibility(View.VISIBLE);
                        mChangelogText.animate().alpha(1f).setDuration(300).start();
                    } else {
                        mChangelogText.setText("Failed to load changelogs.\nTarget: " + dynamicUrl); // Info debug opsional
                        mChangelogText.setAlpha(1f);
                        mChangelogText.setVisibility(View.VISIBLE);
                    }
                }
            });
        });
    }

    private void renderMarkdown(String markdown) {
        final Markwon markwon = Markwon.builder(requireContext())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        builder
                                .headingTextSizeMultipliers(new float[]{2.0f, 1.5f, 1.2f, 1.0f, 0.83f, 0.67f})
                                .thematicBreakHeight(dip(2))
                                .thematicBreakColor(Color.LTGRAY);
                    }

                    @Override
                    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
                        builder.setFactory(StrongEmphasis.class, (configuration, props) -> new Object[]{
                                new StyleSpan(Typeface.BOLD),
                                new ForegroundColorSpan(Color.parseColor("#2196F3"))
                        });
                    }
                })
                .build();

        markwon.setMarkdown(mChangelogText, markdown);
    }

    private int dip(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }
}