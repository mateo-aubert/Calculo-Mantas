package com.mateo.hojadevuelo.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.mateo.hojadevuelo.R;
import com.mateo.hojadevuelo.data.AircraftCatalog;
import com.mateo.hojadevuelo.databinding.ItemRegistrationBinding;

import java.util.ArrayList;
import java.util.List;

public final class RegistrationAdapter
        extends RecyclerView.Adapter<RegistrationAdapter.RegistrationViewHolder> {

    public interface Listener {
        void onListChanged();
    }

    private final Context context;
    private final AircraftCatalog catalog;
    private final Listener listener;
    private final List<String> values = new ArrayList<>();
    private final List<String> suggestions;

    public RegistrationAdapter(Context context, AircraftCatalog catalog, Listener listener) {
        this.context = context;
        this.catalog = catalog;
        this.listener = listener;
        this.suggestions = catalog.getAllRegistrations();
        setHasStableIds(false);
    }

    @NonNull
    @Override
    public RegistrationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRegistrationBinding binding = ItemRegistrationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        RegistrationViewHolder holder = new RegistrationViewHolder(binding);
        ArrayAdapter<String> dropdownAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_dropdown_item_1line,
                suggestions);
        binding.registrationInput.setAdapter(dropdownAdapter);
        binding.registrationInput.setThreshold(0);
        binding.registrationInput.setOnClickListener(view -> binding.registrationInput.showDropDown());
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RegistrationViewHolder holder, int position) {
        holder.bind(values.get(position));
    }

    @Override
    public int getItemCount() {
        return values.size();
    }

    public void replaceAll(List<String> registrations) {
        int oldSize = values.size();
        values.clear();
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize);
        }
        values.addAll(registrations);
        if (!registrations.isEmpty()) {
            notifyItemRangeInserted(0, registrations.size());
        }
        listener.onListChanged();
    }

    public void addEmptyItem() {
        values.add("");
        int position = values.size() - 1;
        notifyItemInserted(position);
        listener.onListChanged();
    }

    public ArrayList<String> getValues() {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            if (!value.trim().isEmpty()) {
                result.add(AircraftCatalog.normalizeRegistration(value));
            }
        }
        return result;
    }

    final class RegistrationViewHolder extends RecyclerView.ViewHolder {
        private final ItemRegistrationBinding binding;
        private TextWatcher watcher;

        RegistrationViewHolder(ItemRegistrationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.deleteButton.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                values.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, values.size() - position);
                listener.onListChanged();
            });

            binding.registrationInput.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    return;
                }
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                String normalized = AircraftCatalog.normalizeRegistration(
                        binding.registrationInput.getText().toString());
                if (!normalized.equals(binding.registrationInput.getText().toString())) {
                    binding.registrationInput.setText(normalized);
                    binding.registrationInput.setSelection(normalized.length());
                }
            });
        }

        void bind(String registration) {
            if (watcher != null) {
                binding.registrationInput.removeTextChangedListener(watcher);
            }
            binding.registrationInput.setText(registration);
            binding.registrationInput.setSelection(registration.length());
            updateCategory(registration);

            watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    int position = getBindingAdapterPosition();
                    if (position == RecyclerView.NO_POSITION) {
                        return;
                    }
                    values.set(position, text.toString());
                    updateCategory(text.toString());
                    listener.onListChanged();
                }

                @Override
                public void afterTextChanged(Editable editable) {
                }
            };
            binding.registrationInput.addTextChangedListener(watcher);
        }

        private void updateCategory(String registration) {
            String category = catalog.categoryFor(registration);
            binding.categoryBadge.setText(category);
            binding.categoryBadge.setTypeface(null, Typeface.BOLD);

            if (AircraftCatalog.CATEGORY_PREMIUM.equals(category)) {
                binding.categoryBadge.setTextColor(ContextCompat.getColor(context, R.color.premium));
                binding.categoryBadge.setBackgroundResource(R.drawable.bg_badge_premium);
            } else if (AircraftCatalog.CATEGORY_UNKNOWN.equals(category)) {
                binding.categoryBadge.setTextColor(ContextCompat.getColor(context, R.color.warning));
                binding.categoryBadge.setBackgroundResource(R.drawable.bg_badge_invalid);
            } else {
                binding.categoryBadge.setTextColor(ContextCompat.getColor(context, R.color.success));
                binding.categoryBadge.setBackgroundResource(R.drawable.bg_badge_valid);
            }
        }
    }
}
